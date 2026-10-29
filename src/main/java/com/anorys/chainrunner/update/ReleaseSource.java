package com.anorys.chainrunner.update;

import java.io.IOException;
import java.nio.file.Path;

/** Where releases are published. Abstracted so that the update logic can be tested without a network. */
public interface ReleaseSource {

    /** The latest published release (not a draft or a pre-release). */
    Release latest() throws IOException;

    /** The release of the given version. */
    Release release(ReleaseVersion version) throws IOException;

    /** Downloads a release file to {@code target}, replacing it. */
    void download(Release.Asset asset, Path target) throws IOException;

    /** Reads a small release file, such as the checksums, as text. */
    String read(Release.Asset asset) throws IOException;
}
