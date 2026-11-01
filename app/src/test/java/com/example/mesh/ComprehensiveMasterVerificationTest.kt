package com.example.mesh

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Desktop JVM integration tests using production framing, chunks, relays and ACKs.
 * Virtual topology proves protocol behavior, not RF distance or cross-OS interoperability. */
class ComprehensiveMasterVerificationTest {
    private val sender = "Sender#000001"
    private val recipient = "Receiver#000002"
    private val cluster = UUID.randomUUID()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value
    private fun payload(packet: MeshPacket): Pair<MeshPacket.FrameHeader, ByteBuffer> {
        val frame = ByteBuffer.wrap(packet.toByteArray())
        val header = MeshPacket.inspectFrame(frame)
        frame.position(header.payloadOffset); frame.limit(header.payloadOffset + header.payloadLength)
        return header to frame.slice()
    }
    private fun bytes(size: Int) = ByteArray(size) { ((it * 31 + it / 251) % 256).toByte() }

    @Test(timeout=30000) fun textUnicodeJsonOverRealLoopback() = runBlocking {
        val records = listOf("Hello", "नमस्ते 🌐🎙️", JSONObject().put("message", "हिन्दी 🚀").toString())
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        try {
            val read = async(Dispatchers.IO) { server.accept().use { socket ->
                socket.soTimeout = 5000
                records.map { MeshPacket.readFrom(socket.getInputStream()).payload.toString(Charsets.UTF_8) }
            } }
            Socket(InetAddress.getLoopbackAddress(),server.localPort).use { socket ->
                records.forEach { MeshPacket(PacketType.TEXT,sender,cluster,it.toByteArray(Charsets.UTF_8),recipient).forNextHop().writeTo(socket.getOutputStream()) }
            }
            assertEquals(records,read.await())
            assertEquals("हिन्दी 🚀",JSONObject(records.last()).getString("message"))
        } finally { server.close() }
    }

    @Test(timeout=60000) fun megabyteJsonUsesFileTransferNotOversizeChatFrame() {
        val text=JSONObject().put("records", "हिन्दी 🚀 ".repeat(100000)).toString()
        val source=text.toByteArray(Charsets.UTF_8)
        assertTrue(source.size>1024*1024)
        assertTrue(runCatching { MeshPacket(PacketType.TEXT,sender,cluster,source,recipient) }.isFailure)
        val received=transfer(source,"records.json",65536)
        assertEquals(text,JSONObject(received.toString(Charsets.UTF_8)).toString())
    }

    @Test(timeout=120000) fun audioRecordingBinaryMetadataAndReassembly() {
        // Opaque recording-like payloads; this is not a MediaCodec or audio playback test.
        for(size in listOf(512*1024,2*1024*1024,5*1024*1024)) for(ext in listOf("m4a","mp3")) {
            val input=bytes(size)
            assertArrayEquals(input,transfer(input,"recording.$ext",65536))
        }
    }
    @Test(timeout=180000) fun video10And50MiBWith64And128KiBTransportBuffers() {
        for(size in listOf(10*1024*1024,50*1024*1024)) for(window in listOf(65536,131072)) {
            val input=bytes(size)
            assertArrayEquals(digest(input),digest(transfer(input,"video.mp4",window)))
        }
    }
    @Test(timeout=60000) fun zipMultiFileCrcAndSha256() {
        val entries=mapOf("notes/hello.txt" to "नमस्ते 🚀".toByteArray(),"data/random.bin" to bytes(1024*1024),"empty.txt" to byteArrayOf())
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { (name,data) -> zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry() } }
        val original=output.toByteArray();val received=transfer(original,"bundle.zip",131072)
        assertEquals(crc(original),crc(received));assertArrayEquals(digest(original),digest(received))
        ZipInputStream(ByteArrayInputStream(received)).use { zip ->
            var count=0
            while(true){val entry=zip.nextEntry ?: break;assertArrayEquals(entries[entry.name],zip.readBytes());count++;zip.closeEntry()}
            assertEquals(entries.size,count)
        }
    }
    @Test(timeout=45000) fun socketDropFailsCleanlyAndFreshTransferRecovers() {
        val source=bytes(2*1024*1024)
        val started=System.nanoTime()
        assertTrue(runCatching { transfer(source,"interrupted.mp4",65536,dropAfter=2) }.isFailure)
        assertTrue((System.nanoTime()-started)/1_000_000<15000)
        assertArrayEquals(source,transfer(source,"retry.mp4",131072))
    }
    @Test(timeout=10000) fun stalledReadTimesOutAndExecutorRemainsResponsive() = runBlocking {
        ServerSocket(0,1,InetAddress.getLoopbackAddress()).use { listener ->
            Socket(InetAddress.getLoopbackAddress(),listener.localPort).use { client ->
                listener.accept().use { accepted ->
                    client.soTimeout=150
                    val failure=withContext(Dispatchers.IO) { runCatching { MeshPacket.readFrom(client.getInputStream()) }.exceptionOrNull() }
                    assertTrue(failure is SocketTimeoutException)
                    withTimeout(1000) { assertEquals(42,async(Dispatchers.IO){42}.await()) }
                }
            }
        }
    }

    private fun transfer(source: ByteArray,name: String,window: Int,dropAfter: Int?=null): ByteArray = runBlocking {
        withTimeout(30000) {
            val root=Files.createTempDirectory("srmesh-master-").toFile()
            val listener=ServerSocket(0,1,InetAddress.getLoopbackAddress())
            val client=Socket(InetAddress.getLoopbackAddress(),listener.localPort)
            val server=listener.accept();listener.close()
            client.soTimeout=10000;server.soTimeout=10000
            val closing=AtomicBoolean(false);val transportError=AtomicReference<Throwable?>()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val reverse=UUID.randomUUID()
            val clientOut=BufferedOutputStream(client.getOutputStream(),window)
            val serverOut=BufferedOutputStream(server.getOutputStream(),window)
            val senderAcks=AckManager(sender,{_,_->error("Sender cannot reply")})
            val receiverAcks=AckManager(recipient,{_,packet-> synchronized(serverOut) { packet.forNextHop().writeTo(serverOut);serverOut.flush() } })
            val receiver=ChunkManager(recipient,File(root,"receiver"),receiverAcks,{error("Unexpected receiver packet")})
            var sentFragments=0
            val send=ChunkManager(sender,File(root,"sender-unused"),senderAcks,{ packet ->
                if(packet.type==PacketType.MEDIA_CHUNK && ++sentFragments==dropAfter){client.close();throw IOException("Injected socket drop")}
                synchronized(clientOut){packet.forNextHop().writeTo(clientOut);clientOut.flush()}
            })
            var expectedFragment=0L
            val reading=scope.launch {
                try {
                    val input=BufferedInputStream(server.getInputStream(),window)
                    while(isActive){
                        val packet=MeshPacket.readFrom(input);val (header,data)=payload(packet)
                        if(packet.type==PacketType.PRE_ALERT){
                            assertEquals(1024,packet.payload.size)
                            val meta=ChunkManager.decodeMetadata(data.duplicate())
                            assertEquals(name,meta.fileName);assertEquals(source.size.toLong(),meta.size);assertArrayEquals(digest(source),meta.sha256)
                        }
                        if(packet.type==PacketType.MEDIA_CHUNK){
                            val fragment=data.duplicate();val index=fragment.getLong(20)*16+fragment.getInt(28)
                            assertEquals(expectedFragment++,index)
                        }
                        receiverAcks.observe(reverse,header,data.duplicate())
                        assertTrue(receiver.consume(header,data))
                    }
                } catch(t:Throwable){if(!closing.get())transportError.compareAndSet(null,t)}
            }
            val acks=scope.launch {
                try {val input=client.getInputStream();while(isActive){val (h,p)=payload(MeshPacket.readFrom(input));assertTrue(senderAcks.accept(h,p))}}
                catch(t:Throwable){if(!closing.get())transportError.compareAndSet(null,t)}
            }
            try {
                val file=File(root,name);file.writeBytes(source)
                val progress=mutableListOf<Long>()
                val id=send.sendFile(file,recipient,cluster,readyTimeoutMs=5000,deliveryTimeoutMs=10000,onProgress={sent,total->
                    assertEquals(source.size.toLong(),total);assertTrue(sent in 0..total)
                    if(progress.isNotEmpty())assertTrue(sent>=progress.last());progress+=sent
                })
                assertNull(transportError.get());assertEquals(0L,progress.first());assertEquals(source.size.toLong(),progress.last())
                assertEquals(AckManager.DeliveryState.DELIVERED,senderAcks.states.value[id])
                assertEquals(ChunkManager.fragmentCount(source.size.toLong()),expectedFragment)
                val received=checkNotNull(receiver.completed.value[id]).file.readBytes()
                assertArrayEquals(source,received);assertArrayEquals(digest(source),digest(received));assertEquals(crc(source),crc(received))
                received
            } finally {
                closing.set(true);client.close();server.close();reading.cancelAndJoin();acks.cancelAndJoin()
                send.close(true);receiver.close(true);senderAcks.close();receiverAcks.close();scope.cancel();root.deleteRecursively()
            }
        }
    }

    @Test(timeout=15000) fun fiveNodeRelayTtlDuplicatesAckAndManualAlternateRoute() = runBlocking {
        val nodes=listOf("A","B","C","D","E","F").associateWith { "$it#000001" }
        var path=listOf("A","B","C","D","E")
        val down=mutableSetOf<String>();val delivered=mutableListOf<MeshPacket.FrameHeader>();val returned=mutableListOf<MeshPacket.FrameHeader>()
        val filters=mutableMapOf<String,RelayPacketFilter>();val edges=mutableMapOf<UUID,Pair<String,String>>()
        val observed=mutableListOf<Pair<String,Int>>()
        fun edge(from:String,to:String)=edges.entries.firstOrNull { it.value==(from to to) }?.key ?: UUID.randomUUID().also {edges[it]=from to to}
        for(node in nodes.keys){
            filters[node]=RelayPacketFilter(nodes.getValue(node),{h,_->if(node=="E")delivered+=h else if(node=="A")returned+=h else error("Unexpected local delivery")},
                {h,_->val order=if(h.targetId==nodes["A"])path.reversed() else path;val at=order.indexOf(node);if(at>=0 && at<order.lastIndex)listOf(edge(node,order[at+1])) else emptyList()},
                {session,frame,_->val (_,next)=edges.getValue(session);if(next in down) false else {
                    val copy=ByteArray(frame.remaining());frame.duplicate().get(copy)
                    filters.getValue(next).receive(session,ByteArrayInputStream(copy)){};true
                }},onFrame={_,h,_->observed+=node to h.currentHops})
        }
        suspend fun inject(packet:MeshPacket)=filters.getValue("B").receive(edge("A","B"),ByteArrayInputStream(packet.forNextHop().toByteArray())){}
        try {
            val p=MeshPacket(PacketType.TEXT,nodes.getValue("A"),cluster,"hello".toByteArray(),nodes["E"])
            inject(p);assertEquals(listOf("B" to 1,"C" to 2,"D" to 3,"E" to 4),observed)
            assertEquals(21,delivered.single().hopLimit)
            assertEquals(RelayPacketFilter.Reason.DUPLICATE,inject(p).reason)
            val ack=MeshPacket(PacketType.DELIVERY_ACK,nodes.getValue("E"),cluster,byteArrayOf(1),nodes["A"])
            filters.getValue("D").receive(edge("E","D"),ByteArrayInputStream(ack.forNextHop().toByteArray())){}
            assertEquals(4,returned.single().currentHops) // Transport-level ACK envelope; real transfer ACKs tested above.
            assertEquals(RelayPacketFilter.Reason.TTL_EXPIRED,inject(MeshPacket(PacketType.TEXT,nodes.getValue("A"),cluster,byteArrayOf(),nodes["E"],hopLimit=1)).reason)
            down+="C"
            assertEquals(RelayPacketFilter.Reason.WRITE_FAILED,inject(MeshPacket(PacketType.TEXT,nodes.getValue("A"),cluster,byteArrayOf(2),nodes["E"])).reason)
            path=listOf("A","B","F","D","E") // Explicitly supplied alternate route, NOT automatic discovery.
            inject(MeshPacket(PacketType.TEXT,nodes.getValue("A"),cluster,byteArrayOf(3),nodes["E"]))
            assertEquals(2,delivered.size);assertTrue(observed.any {it.first=="F"})
        } finally {filters.values.forEach {it.close()}}
    }
    @Ignore("Not implemented: MeshEngine has no automatic alternate-route discovery after losing C. Manual route injection is tested separately.")
    @Test fun automaticRerouteAfterNodeFailure() { fail("Requires route advertisement/discovery integration") }
    @Ignore("A loopback topology has no RF distance. 5 km coverage requires integrated relays and field measurements; TTL is 25.")
    @Test fun fiveKilometreCoverage() { fail("No physical distance can be asserted by this suite") }
    @Ignore("Synthetic .m4a/.mp3 bytes verify transport only; MediaCodec, AudioRecord and cross-OS playback require device/runtime testing.")
    @Test fun nativeAudioCodecAndCrossPlatformHardwareInterop() { fail("Not a JVM capability") }

    @Test(timeout=15000) fun packetFormatRegression() = runMeshPacketChecks()
    @Test(timeout=20000) fun clusterRoutesHeartbeatRegression() = runStep2Checks()
    @Test(timeout=30000) fun boundedRamRelayRegression() = runStep3Checks()
    @Test(timeout=120000) fun chunksPriorityReverseAckCorruptionRegression() = runStep4Checks(emptyArray())
    @Test(timeout=15000) fun voiceWireRegression() = runStep5Checks()
    @Test(timeout=15000) fun progressUiRegression() = runStep6Checks()
    @Test(timeout=15000) fun consentRegression() = runConsentChecks()
    @Test(timeout=30000) fun tcpWebSocketBridgeRegression() = runGatewayRelayChecks()
}
