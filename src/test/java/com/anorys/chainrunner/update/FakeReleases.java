package com.anorys.chainrunner.update;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Releases held in memory: file contents by asset name. */
public final class FakeReleases implements ReleaseSource {

    private final Map<ReleaseVersion, Release> releases = new HashMap<>();
    private final Map<URI, byte[]> files = new HashMap<>();
    private Release latest;
    public int lookups;

    public FakeReleases add(String version, Map<String, byte[]> contents) {
        ReleaseVersion parsed = ReleaseVersion.parse(version).orElseThrow();
        Map<String, Release.Asset> assets = new HashMap<>();
        contents.forEach((name, bytes) -> {
            URI url = URI.create("https://example.test/v" + version + "/" + name);
            assets.put(name, new Release.Asset(name, url, bytes.length));
            files.put(url, bytes);
        });
        Release release = new Release(parsed, URI.create("https://example.test/v" + version), assets);
        releases.put(parsed, release);
        if (latest == null || parsed.isNewerThan(latest.version())) {
            latest = release;
        }
        return this;
    }

    @Override
    public Release latest() throws IOException {
        lookups++;
        if (latest == null) {
            throw new IOException("offline");
        }
        return latest;
    }

    @Override
    public Release release(ReleaseVersion version) throws IOException {
        Release release = releases.get(version);
        if (release == null) {
            throw new IOException("no such release: " + version);
        }
        return release;
    }

    @Override
    public void download(Release.Asset asset, Path target) throws IOException {
        Files.write(target, files.get(asset.url()));
    }

    @Override
    public String read(Release.Asset asset) {
        return new String(files.get(asset.url()), StandardCharsets.UTF_8);
    }
}
