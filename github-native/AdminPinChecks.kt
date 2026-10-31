package checks
fun main() {
    val verifiers = listOf<(String) -> Boolean>(
        com.example.mesh.DynamicConfigManager::verifyAdminPin,
        com.example.mesh.github.DynamicConfigManager::verifyAdminPin
    )
    for (verify in verifiers) {
        check(verify("1990"))
        for (wrong in listOf("", "0000", "8492", "199", "19900", "01990", " 1990", "1990 ", "abcdefgh", "9".repeat(1000))) {
            check(!verify(wrong)) { "Unexpected PIN acceptance" }
        }
    }
    println("Both admin PIN verifiers passed correct, incorrect, malformed and oversized input checks")
}
