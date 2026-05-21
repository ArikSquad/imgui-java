package imgui.vk;

import imgui.ImDrawData;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;

public class ImGuiVulkan {
    public ImGuiVulkan(VkInitInfo info) {
        if (info.instance() == null || info.physicalDevice() == null || info.device() == null || info.queue() == null) {
            throw new IllegalArgumentException("Vulkan init requires non-null instance, physicalDevice, device, and queue");
        }

        var addr = VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr");
        if (addr == 0L) throw new RuntimeException("getInstanceProcAddr failed, how");

        init(VK13.VK_API_VERSION_1_3, addr, info.instance().address(), info.physicalDevice().address(), info.device().address(), info.queue().address(), info);
    }

    private native void init(int apiVersion, long instanceProcAddr, long instanceAddr, long physicalDeviceAddr, long deviceAddr, long queueAddr, VkInitInfo info);

    public native void newFrame();

    public void renderDrawData(ImDrawData drawData, VkCommandBuffer cmdBuf) {
        renderDraw(drawData.ptr, cmdBuf.address());
    }

    private native void renderDraw(long ptr, long address);

    public native void shutdown();

    /**
     * @return VkDescriptorSet handle
     */
    public native long addTexture(long imageView, int imageLayout);

    public native void deleteTexture(long descriptorSet);
}
