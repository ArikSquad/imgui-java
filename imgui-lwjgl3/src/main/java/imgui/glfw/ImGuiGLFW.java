package imgui.glfw;

public class ImGuiGLFW {
    public static native void init(long windowHandle);

    public static native void shutdown();

    public static native void newFrame();

    public static native void installCallbacks(long windowHandle);

    public static native void restoreCallbacks(long windowHandle);
}
