package imgui.vk;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

public record VkInitInfo(VkInstance instance, VkPhysicalDevice physicalDevice, VkDevice device, VkQueue queue, int queueFamily, int minImageCount,
                         int imageCount, long descriptorPool, int colorFormat, int depthFormat, long pipelineCache, long sampler) {

}
