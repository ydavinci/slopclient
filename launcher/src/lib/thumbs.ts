import { api } from "./ipc";

/** Thumbnail data URLs, shared by the galleries and the viewer's film strip. */
const cache = new Map<string, Promise<string>>();

export function thumbnail(instanceId: string, fileName: string): Promise<string> {
  const key = `${instanceId}/${fileName}`;
  let url = cache.get(key);
  if (!url) {
    url = api.screenshotThumb(instanceId, fileName);
    // A failed thumbnail is asked for again next time.
    url.catch(() => cache.delete(key));
    cache.set(key, url);
  }
  return url;
}

export function forgetThumbnail(instanceId: string, fileName: string) {
  cache.delete(`${instanceId}/${fileName}`);
}
