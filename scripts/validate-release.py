import json
import os
import re
import urllib.request
import urllib.error
import urllib.parse
from pathlib import Path

config = json.loads(Path('config.json').read_text(encoding='utf-8'))
assert set(config) == {'latestVersionCode', 'latestVersionName', 'apkDownloadUrl', 'webClientUrl', 'forceUpdate', 'releaseNotes'}
tag = os.environ['RELEASE_TAG']
assert re.fullmatch(r'v\d+\.\d+\.\d+', tag), 'Use a stable vMAJOR.MINOR.PATCH tag'
assert tag == 'v' + config['latestVersionName'], 'Tag and config version must match'
assert type(config['latestVersionCode']) is int and 0 < config['latestVersionCode'] <= 2147483647
assert type(config['forceUpdate']) is bool
assert config['apkDownloadUrl'] == 'https://github.com/sunilkrrajbhar5-byte/p2p-mesh-network/releases/latest/download/app-debug.apk'
assert config['webClientUrl'] == 'https://sunilkrrajbhar5-byte.github.io/p2p-mesh-network/'
assert isinstance(config['releaseNotes'], str) and len(config['releaseNotes']) <= 16384
if os.environ.get('GITHUB_ACTIONS') == 'true':
    repo = 'sunilkrrajbhar5-byte/p2p-mesh-network'
    assert os.environ['GH_REPO'] == repo, 'This configuration targets the named repository only'
    def request(path, accept='application/vnd.github+json'):
        req = urllib.request.Request('https://api.github.com/repos/' + repo + path, headers={
            'Accept': accept, 'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
            'User-Agent': 'mesh-release-validator', 'X-GitHub-Api-Version': '2022-11-28'})
        with urllib.request.urlopen(req, timeout=30) as response:
            return json.load(response)
    try:
        latest = request('/releases/latest')
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        latest = None
    if latest:
        previous = request('/contents/config.json?ref=' + urllib.parse.quote(latest['tag_name'], safe=''),
                           'application/vnd.github.raw+json')
        assert config['latestVersionCode'] > previous['latestVersionCode'], 'Increase versionCode beyond the latest release'
        assert re.fullmatch(r'v\d+\.\d+\.\d+', latest['tag_name']), 'Inspect the existing nonstandard latest tag before publishing'
        assert tuple(map(int, tag[1:].split('.'))) > tuple(map(int, latest['tag_name'][1:].split('.'))), 'Do not replace latest with an older version name'
Path('build').mkdir(exist_ok=True)
Path('build/release-notes.txt').write_text(config['releaseNotes'], encoding='utf-8')
print('Release configuration valid:', tag, 'version code', config['latestVersionCode'])
