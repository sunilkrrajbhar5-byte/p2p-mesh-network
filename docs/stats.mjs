export const REPOSITORY = 'sunilkrrajbhar5-byte/p2p-mesh-network';
const API = 'https://api.github.com/repos/' + REPOSITORY;
export async function readDownloads(fetcher = fetch, signal) {
  const rows = [];
  let requests = 0;
  async function pages(path) {
    const all = [];
    let url = API + path + '?per_page=100';
    while (url) {
      if (++requests > 45) throw Error('Large history: use the authenticated CLI script to retrieve all releases.');
      const response = await fetcher(url, {signal, headers: {Accept:'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28'}});
      if (!response.ok) {
        if (response.status === 403 || response.status === 429) throw Error('GitHub API rate limit reached. Wait before refreshing; do not put a token in this page.');
        if (response.status === 404) throw Error('Public repository/releases not available yet.');
        throw Error('GitHub API returned HTTP ' + response.status);
      }
      const data = await response.json();
      if (!Array.isArray(data)) throw Error('Unexpected API response');
      all.push(...data);
      const next = (response.headers.get('link') || '').match(/<([^>]+)>;\s*rel="next"/);
      url = next ? next[1] : null;
      if (url && !url.startsWith(API + '/')) throw Error('Unexpected pagination origin');
    }
    return all;
  }
  for (const release of await pages('/releases')) {
    if (release.draft) continue;
    const assets = await pages('/releases/' + encodeURIComponent(release.id) + '/assets');
    for (const asset of assets) {
      if (['app-debug.apk', 'SR-Mesh.apk'].includes(asset.name) && Number.isSafeInteger(asset.download_count) && asset.download_count >= 0) {
        rows.push({tag:release.tag_name,downloads:asset.download_count});
      }
    }
  }
  return {total:rows.reduce((sum,row)=>sum+row.downloads,0),rows,checkedAt:new Date().toISOString()};
}
