package com.anorys.chainrunner.update;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/** A published release: its version, its web page and its downloadable files by name. */
public record Release(ReleaseVersion version, URI page, Map<String, Asset> assets) {

    /** The checksum file published with every release ({@code sha256sum} format). */
    public static final String CHECKSUMS = "SHA256SUMS";

    /**
     * A downloadable file.
     *
     * @param url the address to download it from (on GitHub, the API address of the asset, which also
     *            works for private repositories)
     */
    public record Asset(String name, URI url, long size) {
    }

    public Release {
        assets = Map.copyOf(assets);
    }

    public Optional<Asset> asset(String name) {
        return Optional.ofNullable(assets.get(name));
    }
}
