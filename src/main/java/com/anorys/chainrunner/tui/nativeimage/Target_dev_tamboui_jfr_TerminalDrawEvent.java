package com.anorys.chainrunner.tui.nativeimage;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * TamboUI asks Flight Recorder on every frame whether its draw event is enabled. The native image is
 * built without JFR, where that question fails with an {@link UnsatisfiedLinkError}: the event is
 * reported as disabled instead. The class is also initialized while building (see
 * {@code META-INF/native-image/com.anorys.chainrunner/chainrunner/native-image.properties}), because its JFR
 * superclass cannot be initialized at run time. Only native-image reads this class.
 */
@TargetClass(className = "dev.tamboui.jfr.TerminalDrawEvent")
final class Target_dev_tamboui_jfr_TerminalDrawEvent {

    @Substitute
    public static boolean enabled() {
        return false;
    }
}
