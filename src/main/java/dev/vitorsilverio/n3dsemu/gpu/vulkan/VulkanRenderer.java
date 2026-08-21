package dev.vitorsilverio.n3dsemu.gpu.vulkan;

import dev.vitorsilverio.n3dsemu.gpu.FrameBufferCodec;
import dev.vitorsilverio.n3dsemu.gpu.PicaRenderer;
import dev.vitorsilverio.n3dsemu.gpu.PixelFormat;
import dev.vitorsilverio.n3dsemu.gpu.Screen;
import dev.vitorsilverio.n3dsemu.gpu.ShadedVertex;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/// {@link PicaRenderer} real (RFC-N3DSEMU G4/D4): janela GLFW própria + Vulkan via LWJGL 3, sem
/// backend de software (decisão explícita do usuário). Escopo mínimo do marco M4 — só o *blit*
/// dos framebuffers do guest como duas texturas sobre um quad de tela cheia cada, SEM interpretar
/// nenhuma lista de comando da PICA200 (isso é a G5).
///
/// **Nenhum teste automatizado valida esta classe de verdade** (RFC D4: "nenhuma task da trilha G
/// pode ter como aceite automatizado 'o triângulo apareceu'") — a validação é visual, pelo
/// usuário. Quem constrói esta classe em CI/sem GPU deve capturar {@link VulkanUnavailableException}
/// e pular via `Assumptions.assumeTrue` (RFC/task: "o runner do GitHub não tem GPU/driver
/// Vulkan").
public final class VulkanRenderer implements PicaRenderer {
    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";
    private static final String VALIDATION_PROPERTY = "n3dsemu.vulkan.validation";
    /// Simula ausência de GPU/driver Vulkan (RFC/task, Aceite: "simule... numa máquina sem
    /// Vulkan") sem precisar de um host real sem driver — usado pelo teste de fumaça desta
    /// classe para exercitar o caminho `Assumptions.assumeTrue` também numa máquina COM GPU.
    private static final String FORCE_UNAVAILABLE_PROPERTY = "n3dsemu.vulkan.force-unavailable";
    /// Cria a janela GLFW **oculta** (`GLFW_VISIBLE=false`) — usado pelos testes de fumaça desta
    /// classe (`VulkanRendererSmokeTest`), que precisam exercitar a pilha Vulkan real (instância/
    /// swapchain/*render pass*/apresentação) sem depender de GPU alguma ficar visível. Achado
    /// real (2026-08-21): sem isto, `mvn test` abria uma janela real na tela a cada execução —
    /// como o teste desenha um triângulo de teste sintético (RGB, hardcoded, nada a ver com
    /// nenhuma ROM), isso já confundiu mais de uma sessão de depuração fazendo parecer que
    /// `simple_tri.3dsx`/G5.1 tinha funcionado quando na verdade era só este teste passando. `Main`
    /// (uso real, modo janela) nunca define esta propriedade — continua visível por padrão.
    private static final String HIDDEN_WINDOW_PROPERTY = "n3dsemu.vulkan.hidden-window";
    private static final int FRAMES_IN_FLIGHT = 2;
    private static final long NO_TIMEOUT = -1L; // UINT64_MAX quando passado como long para vkWaitForFences/vkAcquireNextImageKHR

    /// Layout do quad de tela cheia (`present.vert`): TRIANGLE_STRIP de 4 vértices, sem vertex
    /// buffer.
    private static final int QUAD_VERTEX_COUNT = 4;
    /// `push_constant` de `present.vert`: `vec4 rect` (16 bytes).
    private static final int PUSH_CONSTANT_SIZE_BYTES = 16;

    /// `vec2` posição NDC + `vec4` cor (RFC G5/PR2) — ver `shaders/triangle.vert`.
    private static final int GEOMETRY_VERTEX_STRIDE_BYTES = 6 * Float.BYTES;

    private final long window;
    private final VkInstance instance;
    private final long surface;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final VkQueue graphicsQueue;
    private final VkQueue presentQueue;
    private final int graphicsQueueFamily;
    private final long commandPool;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long pipelineLayout;
    private final long renderPass;
    private final long sampler;

    /// Pipeline de geometria (RFC G5/PR2): desenha triângulos já sombreados (posição NDC + cor
    /// por vértice, sem textura) DIRETO na {@link ScreenTexture#image} de destino — reaproveita
    /// 100% do caminho de apresentação/rotação já validado na G4 (a MESMA textura que o *blit* da
    /// CPU alimenta), em vez de inventar um caminho paisagem paralelo. Ainda **sem TEV** (PR3).
    private final long geometryRenderPass;
    private final long geometryPipelineLayout;
    private final long geometryPipeline;

    private long swapchain;
    private int swapchainImageFormat;
    private VkExtent2D swapchainExtent;
    private long[] swapchainImages;
    private long[] swapchainImageViews;
    private long[] swapchainFramebuffers;
    private long graphicsPipeline;

    private final long[] imageAvailableSemaphores = new long[FRAMES_IN_FLIGHT];
    private final long[] renderFinishedSemaphores = new long[FRAMES_IN_FLIGHT];
    private final long[] inFlightFences = new long[FRAMES_IN_FLIGHT];
    private final VkCommandBuffer[] commandBuffers = new VkCommandBuffer[FRAMES_IN_FLIGHT];
    private int currentFrame;

    private final Map<Screen, ScreenTexture> textures = new EnumMap<>(Screen.class);
    private final Map<Screen, List<ShadedVertex>> pendingTriangles = new EnumMap<>(Screen.class);
    /// Recursos (ex.: *vertex buffers* descartáveis de {@link #drawPendingTriangles}) só podem
    /// ser liberados depois que a GPU terminar de usá-los — um balde por *frame in flight*,
    /// esvaziado logo após {@code vkWaitForFences} confirmar que aquele *slot* está livre de
    /// novo (RFC G5/PR2, "sem otimização": um `vkDestroyBuffer` por desenho é aceitável aqui).
    private final List<Runnable>[] frameCleanup;
    private boolean framebufferResized;
    private boolean closed;

    /// Recursos de uma tela: textura RETRATO (largura=`Screen.ROWS`, altura=`screen.columns()` —
    /// a rotação é feita no shader, nunca aqui) + buffer de staging persistente (mapeado uma vez)
    /// para o upload de {@link #presentScreen} + um framebuffer próprio (`geometryFramebuffer`)
    /// para {@link #geometryRenderPass} desenhar geometria direto nesta MESMA imagem (RFC G5/PR2).
    private final class ScreenTexture {
        final int width;
        final int height;
        long image;
        long imageMemory;
        long imageView;
        long descriptorSet;
        long stagingBuffer;
        long stagingMemory;
        ByteBuffer stagingMapped;
        boolean everUploaded;
        byte[] pendingRgba8;
        long geometryFramebuffer;

        ScreenTexture(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    /// Cria a janela + toda a pilha Vulkan. Lança {@link VulkanUnavailableException} se não
    /// houver GPU/driver Vulkan disponível (RFC/task: CI não tem).
    public VulkanRenderer() {
        if (Boolean.getBoolean(FORCE_UNAVAILABLE_PROPERTY)) {
            throw new VulkanUnavailableException(FORCE_UNAVAILABLE_PROPERTY + "=true");
        }
        boolean validation = Boolean.getBoolean(VALIDATION_PROPERTY);
        try {
            if (!glfwInit()) {
                throw new VulkanUnavailableException("glfwInit falhou");
            }
            if (!glfwVulkanSupported()) {
                throw new VulkanUnavailableException("GLFW reporta que Vulkan não está disponível neste host");
            }
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
            if (Boolean.getBoolean(HIDDEN_WINDOW_PROPERTY)) {
                glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            }
            // RFC/task: as duas telas empilhadas verticalmente, topo 400 de largura.
            this.window = glfwCreateWindow(400, 240 + 320, "n3dsemu", MemoryUtil.NULL, MemoryUtil.NULL);
            if (window == MemoryUtil.NULL) {
                throw new VulkanUnavailableException("glfwCreateWindow falhou");
            }
            glfwSetFramebufferSizeCallback(window, (w, width, height) -> framebufferResized = true);

            this.instance = createInstance(validation);
            this.surface = createSurface();
            this.physicalDevice = pickPhysicalDevice();
            this.graphicsQueueFamily = findGraphicsQueueFamily();
            this.device = createLogicalDevice(validation);
            this.graphicsQueue = getQueue(graphicsQueueFamily);
            this.presentQueue = graphicsQueue; // simplificação: mesma fila serve os dois papéis nesta task
            this.commandPool = createCommandPool();
            this.descriptorSetLayout = createDescriptorSetLayout();
            this.descriptorPool = createDescriptorPool();
            this.pipelineLayout = createPipelineLayout();
            this.renderPass = createRenderPass();
            this.sampler = createSampler();
            this.geometryRenderPass = createGeometryRenderPass();
            this.geometryPipelineLayout = createGeometryPipelineLayout();
            createSwapchainAndDependents();
            createCommandBuffers();
            createSyncObjects();
            for (Screen screen : Screen.values()) {
                textures.put(screen, createScreenTexture(screen));
            }
            this.geometryPipeline = createGeometryPipeline();
            this.frameCleanup = newFrameCleanupArray();
        } catch (VulkanUnavailableException e) {
            throw e;
        } catch (LinkageError | RuntimeException e) {
            // LinkageError (UnsatisfiedLinkError/NoClassDefFoundError): o runner do GitHub não
            // tem libvulkan.so.1/vulkan-1.dll instalada — o carregamento nativo do LWJGL falha
            // aqui, não com uma RuntimeException comum (RFC/task: "o runner do GitHub não tem
            // GPU/driver Vulkan"). Sem capturar isto também, o Error escaparia do
            // `Assumptions.assumeTrue` de quem chama e derrubaria a suíte em vez de pular.
            throw new VulkanUnavailableException("falha ao inicializar Vulkan: " + e.getMessage(), e);
        }
    }

    // ── ciclo de vida da janela (Main) ──────────────────────────────────────────────────────

    public boolean shouldClose() {
        return glfwWindowShouldClose(window);
    }

    public void pollEvents() {
        glfwPollEvents();
    }

    public long windowHandle() {
        return window;
    }

    // ── PicaRenderer ────────────────────────────────────────────────────────────────────────

    @Override
    public void drawTriangles(Screen screen, List<ShadedVertex> vertices) {
        pendingTriangles.put(screen, List.copyOf(vertices));
    }

    @Override
    public void presentScreen(Screen screen, byte[] pixels, PixelFormat format, int stride) {
        textures.get(screen).pendingRgba8 = FrameBufferCodec.decodeToRgba8(pixels, screen, format, stride);
    }

    @Override
    public void endFrame() {
        renderFrame();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        vkDeviceWaitIdle(device);
        for (List<Runnable> cleanup : frameCleanup) {
            runAndClear(cleanup);
        }
        for (ScreenTexture texture : textures.values()) {
            vkDestroyFramebuffer(device, texture.geometryFramebuffer, null);
            vkDestroyImageView(device, texture.imageView, null);
            vkDestroyImage(device, texture.image, null);
            vkFreeMemory(device, texture.imageMemory, null);
            if (texture.stagingMapped != null) {
                vkUnmapMemory(device, texture.stagingMemory);
            }
            vkDestroyBuffer(device, texture.stagingBuffer, null);
            vkFreeMemory(device, texture.stagingMemory, null);
        }
        vkDestroyPipeline(device, geometryPipeline, null);
        vkDestroyPipelineLayout(device, geometryPipelineLayout, null);
        vkDestroyRenderPass(device, geometryRenderPass, null);
        vkDestroySampler(device, sampler, null);
        destroySwapchainAndDependents();
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            vkDestroySemaphore(device, imageAvailableSemaphores[i], null);
            vkDestroySemaphore(device, renderFinishedSemaphores[i], null);
            vkDestroyFence(device, inFlightFences[i], null);
        }
        vkDestroyDescriptorPool(device, descriptorPool, null);
        vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyRenderPass(device, renderPass, null);
        vkDestroyCommandPool(device, commandPool, null);
        vkDestroyDevice(device, null);
        vkDestroySurfaceKHR(instance, surface, null);
        vkDestroyInstance(instance, null);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    // ── instância / dispositivo ─────────────────────────────────────────────────────────────

    private VkInstance createInstance(boolean validation) {
        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("n3dsemu"))
                    .applicationVersion(VK10.VK_MAKE_VERSION(1, 0, 0))
                    .pEngineName(stack.UTF8("n3dsemu"))
                    .engineVersion(VK10.VK_MAKE_VERSION(1, 0, 0))
                    .apiVersion(VK10.VK_API_VERSION_1_0);

            PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();
            if (glfwExtensions == null) {
                throw new VulkanUnavailableException("glfwGetRequiredInstanceExtensions devolveu null (sem loader Vulkan)");
            }
            PointerBuffer extensions;
            if (validation) {
                extensions = stack.mallocPointer(glfwExtensions.remaining() + 1);
                extensions.put(glfwExtensions).put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)).flip();
            } else {
                extensions = glfwExtensions;
            }

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(extensions);

            if (validation && validationLayerAvailable(stack)) {
                createInfo.ppEnabledLayerNames(stack.pointers(stack.UTF8(VALIDATION_LAYER)));
            }

            PointerBuffer pInstance = stack.mallocPointer(1);
            check(vkCreateInstance(createInfo, null, pInstance), "vkCreateInstance");
            return new VkInstance(pInstance.get(0), createInfo);
        }
    }

    private boolean validationLayerAvailable(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumerateInstanceLayerProperties(count, null);
        if (count.get(0) == 0) {
            return false;
        }
        VkLayerProperties.Buffer layers = VkLayerProperties.malloc(count.get(0), stack);
        vkEnumerateInstanceLayerProperties(count, layers);
        for (VkLayerProperties layer : layers) {
            if (VALIDATION_LAYER.equals(layer.layerNameString())) {
                return true;
            }
        }
        return false;
    }

    private long createSurface() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSurface = stack.mallocLong(1);
            check(glfwCreateWindowSurface(instance, window, null, pSurface), "glfwCreateWindowSurface");
            return pSurface.get(0);
        }
    }

    private VkPhysicalDevice pickPhysicalDevice() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, count, null);
            if (count.get(0) == 0) {
                throw new VulkanUnavailableException("nenhum dispositivo físico Vulkan encontrado");
            }
            PointerBuffer devices = stack.mallocPointer(count.get(0));
            vkEnumeratePhysicalDevices(instance, count, devices);
            for (int i = 0; i < devices.capacity(); i++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
                if (hasGraphicsAndPresentSupport(candidate, stack)) {
                    return candidate;
                }
            }
            throw new VulkanUnavailableException("nenhum dispositivo físico com fila gráfica+apresentação");
        }
    }

    private boolean hasGraphicsAndPresentSupport(VkPhysicalDevice candidate, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
        IntBuffer presentSupport = stack.mallocInt(1);
        for (int i = 0; i < families.capacity(); i++) {
            boolean graphics = (families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0;
            vkGetPhysicalDeviceSurfaceSupportKHR(candidate, i, surface, presentSupport);
            if (graphics && presentSupport.get(0) == VK_TRUE) {
                return true;
            }
        }
        return false;
    }

    private int findGraphicsQueueFamily() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, families);
            IntBuffer presentSupport = stack.mallocInt(1);
            for (int i = 0; i < families.capacity(); i++) {
                boolean graphics = (families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0;
                vkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice, i, surface, presentSupport);
                if (graphics && presentSupport.get(0) == VK_TRUE) {
                    return i;
                }
            }
            throw new VulkanUnavailableException("fila gráfica+apresentação não encontrada");
        }
    }

    private VkDevice createLogicalDevice(boolean validation) {
        try (MemoryStack stack = stackPush()) {
            FloatBufferOnePriority priority = new FloatBufferOnePriority(stack);
            VkDeviceQueueCreateInfo.Buffer queueCreateInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(graphicsQueueFamily)
                    .pQueuePriorities(priority.buffer());

            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);

            PointerBuffer extensions = stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(queueCreateInfo)
                    .pEnabledFeatures(features)
                    .ppEnabledExtensionNames(extensions);
            if (validation && validationLayerAvailable(stack)) {
                createInfo.ppEnabledLayerNames(stack.pointers(stack.UTF8(VALIDATION_LAYER)));
            }

            PointerBuffer pDevice = stack.mallocPointer(1);
            check(vkCreateDevice(physicalDevice, createInfo, null, pDevice), "vkCreateDevice");
            return new VkDevice(pDevice.get(0), physicalDevice, createInfo);
        }
    }

    private VkQueue getQueue(int family) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, family, 0, pQueue);
            return new VkQueue(pQueue.get(0), device);
        }
    }

    /// `VkDeviceQueueCreateInfo#pQueuePriorities` exige um `FloatBuffer` vivo enquanto a struct é
    /// usada — pequeno RAII para não vazar o array de prioridades de fora do stack.
    private record FloatBufferOnePriority(MemoryStack stack) {
        java.nio.FloatBuffer buffer() {
            return stack.floats(1.0f);
        }
    }

    // ── comandos / descritores / pipeline (fixos, não dependem do swapchain) ───────────────

    private long createCommandPool() {
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo createInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(graphicsQueueFamily);
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(device, createInfo, null, pPool), "vkCreateCommandPool");
            return pPool.get(0);
        }
    }

    private long createDescriptorSetLayout() {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(binding);
            LongBuffer pLayout = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, createInfo, null, pLayout), "vkCreateDescriptorSetLayout");
            return pLayout.get(0);
        }
    }

    private long createDescriptorPool() {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorPoolSize.Buffer size = VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(Screen.values().length);
            VkDescriptorPoolCreateInfo createInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(size)
                    .maxSets(Screen.values().length);
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateDescriptorPool(device, createInfo, null, pPool), "vkCreateDescriptorPool");
            return pPool.get(0);
        }
    }

    private long createPipelineLayout() {
        try (MemoryStack stack = stackPush()) {
            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT)
                    .offset(0)
                    .size(PUSH_CONSTANT_SIZE_BYTES);
            VkPipelineLayoutCreateInfo createInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(range);
            LongBuffer pLayout = stack.mallocLong(1);
            check(vkCreatePipelineLayout(device, createInfo, null, pLayout), "vkCreatePipelineLayout");
            return pLayout.get(0);
        }
    }

    private long createRenderPass() {
        try (MemoryStack stack = stackPush()) {
            VkAttachmentDescription.Buffer attachment = VkAttachmentDescription.calloc(1, stack)
                    .format(chooseSurfaceFormat(stack))
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack)
                    .attachment(0)
                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorRef);

            VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .srcAccessMask(0)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

            VkRenderPassCreateInfo createInfo = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachment)
                    .pSubpasses(subpass)
                    .pDependencies(dependency);

            LongBuffer pRenderPass = stack.mallocLong(1);
            check(vkCreateRenderPass(device, createInfo, null, pRenderPass), "vkCreateRenderPass");
            return pRenderPass.get(0);
        }
    }

    private long createSampler() {
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo createInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_NEAREST)
                    .minFilter(VK_FILTER_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                    .unnormalizedCoordinates(false)
                    .compareEnable(false)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST);
            LongBuffer pSampler = stack.mallocLong(1);
            check(vkCreateSampler(device, createInfo, null, pSampler), "vkCreateSampler");
            return pSampler.get(0);
        }
    }

    // ── geometria (RFC G5/PR2) ──────────────────────────────────────────────────────────────

    /// *Render pass* dedicado à geometria: mesmo formato de {@link ScreenTexture#image}
    /// (`VK_FORMAT_R8G8B8A8_UNORM`), mas layout final `SHADER_READ_ONLY_OPTIMAL` (a imagem volta
    /// a ser lida pelo pipeline de apresentação da G4 logo em seguida, mesma textura).
    /// `LOAD_OP_CLEAR`: nesta PR geometria e *blit* de CPU não coexistem no mesmo quadro (sem
    /// consumidor real que precise dos dois juntos ainda).
    private long createGeometryRenderPass() {
        try (MemoryStack stack = stackPush()) {
            VkAttachmentDescription.Buffer attachment = VkAttachmentDescription.calloc(1, stack)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack)
                    .attachment(0)
                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorRef);

            VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .srcAccessMask(0)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

            VkRenderPassCreateInfo createInfo = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachment)
                    .pSubpasses(subpass)
                    .pDependencies(dependency);

            LongBuffer pRenderPass = stack.mallocLong(1);
            check(vkCreateRenderPass(device, createInfo, null, pRenderPass), "vkCreateRenderPass (geometria)");
            return pRenderPass.get(0);
        }
    }

    private long createGeometryPipelineLayout() {
        try (MemoryStack stack = stackPush()) {
            VkPipelineLayoutCreateInfo createInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            LongBuffer pLayout = stack.mallocLong(1);
            check(vkCreatePipelineLayout(device, createInfo, null, pLayout), "vkCreatePipelineLayout (geometria)");
            return pLayout.get(0);
        }
    }

    private long createGeometryFramebuffer(ScreenTexture texture) {
        try (MemoryStack stack = stackPush()) {
            VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(geometryRenderPass)
                    .pAttachments(stack.longs(texture.imageView))
                    .width(texture.width)
                    .height(texture.height)
                    .layers(1);
            LongBuffer pFramebuffer = stack.mallocLong(1);
            check(vkCreateFramebuffer(device, framebufferInfo, null, pFramebuffer), "vkCreateFramebuffer (geometria)");
            return pFramebuffer.get(0);
        }
    }

    /// Vértice: `vec2` posição já em NDC (divisão de perspectiva feita em Java, RFC G5/PR2) +
    /// `vec4` cor — sem textura/TEV (PR3). *Viewport*/*scissor* **dinâmicos** (`vkCmdSetViewport`/
    /// `vkCmdSetScissor`): TOP e BOTTOM têm dimensões de textura diferentes, um pipeline serve as
    /// duas.
    private long createGeometryPipeline() {
        try (MemoryStack stack = stackPush()) {
            long vertModule = createShaderModule("shaders/triangle.vert", Shaderc.shaderc_glsl_vertex_shader, stack);
            long fragModule = createShaderModule("shaders/triangle.frag", Shaderc.shaderc_glsl_fragment_shader, stack);
            try {
                VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
                stages.get(0)
                        .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                        .stage(VK_SHADER_STAGE_VERTEX_BIT)
                        .module(vertModule)
                        .pName(stack.UTF8("main"));
                stages.get(1)
                        .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                        .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                        .module(fragModule)
                        .pName(stack.UTF8("main"));

                VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack)
                        .binding(0)
                        .stride(GEOMETRY_VERTEX_STRIDE_BYTES)
                        .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
                VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(2, stack);
                attributes.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
                attributes.get(1).binding(0).location(1).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(8);
                VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                        .pVertexBindingDescriptions(binding)
                        .pVertexAttributeDescriptions(attributes);

                VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                        .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

                VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                        .viewportCount(1)
                        .scissorCount(1);

                VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                        .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

                VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                        .polygonMode(VK_POLYGON_MODE_FILL)
                        .cullMode(VK_CULL_MODE_NONE)
                        .frontFace(VK_FRONT_FACE_CLOCKWISE)
                        .lineWidth(1f);

                VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                        .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

                VkPipelineColorBlendAttachmentState.Buffer blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                        .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                                | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                        .blendEnable(false);
                VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                        .pAttachments(blendAttachment);

                VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
                pipelineInfo.get(0)
                        .sType(VK10.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                        .pStages(stages)
                        .pVertexInputState(vertexInput)
                        .pInputAssemblyState(inputAssembly)
                        .pViewportState(viewportState)
                        .pDynamicState(dynamicState)
                        .pRasterizationState(rasterizer)
                        .pMultisampleState(multisample)
                        .pColorBlendState(colorBlend)
                        .layout(geometryPipelineLayout)
                        .renderPass(geometryRenderPass)
                        .subpass(0);

                LongBuffer pPipeline = stack.mallocLong(1);
                check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline),
                        "vkCreateGraphicsPipelines (geometria)");
                return pPipeline.get(0);
            } finally {
                vkDestroyShaderModule(device, vertModule, null);
                vkDestroyShaderModule(device, fragModule, null);
            }
        }
    }

    private void drawPendingTriangles(VkCommandBuffer commandBuffer, MemoryStack stack) {
        for (Map.Entry<Screen, List<ShadedVertex>> entry : pendingTriangles.entrySet()) {
            ScreenTexture texture = textures.get(entry.getKey());
            List<ShadedVertex> vertices = entry.getValue();
            if (vertices.isEmpty()) {
                continue;
            }

            long[] vertexBufferAndMemory = uploadGeometryVertexBuffer(vertices, stack);
            long vertexBuffer = vertexBufferAndMemory[0];
            long vertexBufferMemory = vertexBufferAndMemory[1];

            VkClearValue.Buffer clearValues = VkClearValue.calloc(1, stack);
            clearValues.get(0).color().float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 1f);
            VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(geometryRenderPass)
                    .framebuffer(texture.geometryFramebuffer)
                    .pClearValues(clearValues);
            renderPassInfo.renderArea().offset().set(0, 0);
            renderPassInfo.renderArea().extent().set(texture.width, texture.height);

            vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, geometryPipeline);

            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0).y(0).width(texture.width).height(texture.height).minDepth(0f).maxDepth(1f);
            vkCmdSetViewport(commandBuffer, 0, viewport);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).offset().set(0, 0);
            scissor.get(0).extent().set(texture.width, texture.height);
            vkCmdSetScissor(commandBuffer, 0, scissor);

            vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(vertexBuffer), stack.longs(0));
            vkCmdDraw(commandBuffer, vertices.size(), 1, 0, 0);
            vkCmdEndRenderPass(commandBuffer);

            // Buffer host-visible descartável por desenho (RFC/task G5, "Não inclui": "sem
            // otimização de desempenho — correção primeiro"); liberado só depois que a fila
            // terminar de usá-lo, senão o driver pode ler memória já desalocada.
            frameCleanup[currentFrame].add(() -> {
                vkDestroyBuffer(device, vertexBuffer, null);
                vkFreeMemory(device, vertexBufferMemory, null);
            });
            texture.everUploaded = true;
        }
        pendingTriangles.clear();
    }

    private long[] uploadGeometryVertexBuffer(List<ShadedVertex> vertices, MemoryStack stack) {
        long sizeBytes = (long) vertices.size() * GEOMETRY_VERTEX_STRIDE_BYTES;
        long[] bufferAndMemory = createBuffer(sizeBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        PointerBuffer pData = stack.mallocPointer(1);
        check(vkMapMemory(device, bufferAndMemory[1], 0, sizeBytes, 0, pData), "vkMapMemory (vertex buffer)");
        ByteBuffer mapped = pData.getByteBuffer(0, (int) sizeBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (ShadedVertex v : vertices) {
            mapped.putFloat(v.ndcX()).putFloat(v.ndcY())
                    .putFloat(v.r()).putFloat(v.g()).putFloat(v.b()).putFloat(v.a());
        }
        vkUnmapMemory(device, bufferAndMemory[1]);
        return bufferAndMemory;
    }

    private int chooseSurfaceFormat(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, null);
        VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, formats);
        for (VkSurfaceFormatKHR format : formats) {
            if (format.format() == VK_FORMAT_B8G8R8A8_UNORM
                    && format.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                return format.format();
            }
        }
        return formats.get(0).format();
    }

    // ── swapchain (recriável em resize) ─────────────────────────────────────────────────────

    private void createSwapchainAndDependents() {
        try (MemoryStack stack = stackPush()) {
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, capabilities);

            int format = chooseSurfaceFormat(stack);
            VkExtent2D extent = chooseExtent(capabilities, stack);
            int imageCount = capabilities.minImageCount() + 1;
            if (capabilities.maxImageCount() > 0) {
                imageCount = Math.min(imageCount, capabilities.maxImageCount());
            }

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                    .surface(surface)
                    .minImageCount(imageCount)
                    .imageFormat(format)
                    .imageColorSpace(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
                    .imageExtent(extent)
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(capabilities.currentTransform())
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(VK_PRESENT_MODE_FIFO_KHR)
                    .clipped(true)
                    .oldSwapchain(VK_NULL_HANDLE);

            LongBuffer pSwapchain = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(device, createInfo, null, pSwapchain), "vkCreateSwapchainKHR");
            this.swapchain = pSwapchain.get(0);
            this.swapchainImageFormat = format;
            this.swapchainExtent = VkExtent2D.create().set(extent);

            IntBuffer count = stack.mallocInt(1);
            vkGetSwapchainImagesKHR(device, swapchain, count, null);
            LongBuffer images = stack.mallocLong(count.get(0));
            vkGetSwapchainImagesKHR(device, swapchain, count, images);
            swapchainImages = new long[count.get(0)];
            images.get(swapchainImages);

            swapchainImageViews = new long[swapchainImages.length];
            for (int i = 0; i < swapchainImages.length; i++) {
                swapchainImageViews[i] = createImageView(swapchainImages[i], format, VK_IMAGE_ASPECT_COLOR_BIT);
            }

            swapchainFramebuffers = new long[swapchainImages.length];
            for (int i = 0; i < swapchainImages.length; i++) {
                LongBuffer attachments = stack.longs(swapchainImageViews[i]);
                VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                        .renderPass(renderPass)
                        .pAttachments(attachments)
                        .width(swapchainExtent.width())
                        .height(swapchainExtent.height())
                        .layers(1);
                LongBuffer pFramebuffer = stack.mallocLong(1);
                check(vkCreateFramebuffer(device, framebufferInfo, null, pFramebuffer), "vkCreateFramebuffer");
                swapchainFramebuffers[i] = pFramebuffer.get(0);
            }

            this.graphicsPipeline = createGraphicsPipeline(stack);
        }
    }

    private VkExtent2D chooseExtent(VkSurfaceCapabilitiesKHR capabilities, MemoryStack stack) {
        if (capabilities.currentExtent().width() != 0xFFFFFFFF) {
            return capabilities.currentExtent();
        }
        IntBuffer w = stack.mallocInt(1);
        IntBuffer h = stack.mallocInt(1);
        glfwGetFramebufferSize(window, w, h);
        return VkExtent2D.malloc(stack).set(
                clamp(w.get(0), capabilities.minImageExtent().width(), capabilities.maxImageExtent().width()),
                clamp(h.get(0), capabilities.minImageExtent().height(), capabilities.maxImageExtent().height()));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void destroySwapchainAndDependents() {
        vkDestroyPipeline(device, graphicsPipeline, null);
        for (long framebuffer : swapchainFramebuffers) {
            vkDestroyFramebuffer(device, framebuffer, null);
        }
        for (long imageView : swapchainImageViews) {
            vkDestroyImageView(device, imageView, null);
        }
        vkDestroySwapchainKHR(device, swapchain, null);
    }

    /// Reagida a `VK_ERROR_OUT_OF_DATE_KHR`/suboptimal ou ao callback de resize do GLFW (RFC/
    /// task, Armadilhas: "se a swapchain ficar OUT_OF_DATE a cada quadro, é redimensionamento não
    /// tratado").
    private void recreateSwapchain() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetFramebufferSize(window, w, h);
            while (w.get(0) == 0 || h.get(0) == 0) {
                glfwGetFramebufferSize(window, w, h);
                glfwWaitEvents();
            }
        }
        vkDeviceWaitIdle(device);
        destroySwapchainAndDependents();
        createSwapchainAndDependents();
        framebufferResized = false;
    }

    // ── shaders / pipeline gráfico ──────────────────────────────────────────────────────────

    private long createGraphicsPipeline(MemoryStack stack) {
        long vertModule = createShaderModule("shaders/present.vert", Shaderc.shaderc_glsl_vertex_shader, stack);
        long fragModule = createShaderModule("shaders/present.frag", Shaderc.shaderc_glsl_fragment_shader, stack);
        try {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT)
                    .module(vertModule)
                    .pName(stack.UTF8("main"));
            stages.get(1)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(fragModule)
                    .pName(stack.UTF8("main"));

            // Sem vertex buffer — present.vert gera as posições a partir do gl_VertexIndex.
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP);

            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0).y(0)
                    .width(swapchainExtent.width())
                    .height(swapchainExtent.height())
                    .minDepth(0f).maxDepth(1f);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).offset().set(0, 0);
            scissor.get(0).extent(swapchainExtent);
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .pViewports(viewport)
                    .pScissors(scissor);

            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .cullMode(VK_CULL_MODE_NONE)
                    .frontFace(VK_FRONT_FACE_CLOCKWISE)
                    .lineWidth(1f);

            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            VkPipelineColorBlendAttachmentState.Buffer blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                            | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(false);
            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .pAttachments(blendAttachment);

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0)
                    .sType(VK10.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(rasterizer)
                    .pMultisampleState(multisample)
                    .pColorBlendState(colorBlend)
                    .layout(pipelineLayout)
                    .renderPass(renderPass)
                    .subpass(0);

            LongBuffer pPipeline = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline),
                    "vkCreateGraphicsPipelines");
            return pPipeline.get(0);
        } finally {
            vkDestroyShaderModule(device, vertModule, null);
            vkDestroyShaderModule(device, fragModule, null);
        }
    }

    private long createShaderModule(String classpathResource, int shaderKind, MemoryStack stack) {
        String source = readResourceAsString(classpathResource);
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == MemoryUtil.NULL) {
            throw new VulkanUnavailableException("shaderc_compiler_initialize falhou");
        }
        try {
            long result = Shaderc.shaderc_compile_into_spv(
                    compiler, source, shaderKind, classpathResource, "main", MemoryUtil.NULL);
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                    throw new VulkanUnavailableException(
                            "falha ao compilar " + classpathResource + ": " + Shaderc.shaderc_result_get_error_message(result));
                }
                ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
                VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                        .pCode(spirv);
                LongBuffer pModule = stack.mallocLong(1);
                check(vkCreateShaderModule(device, createInfo, null, pModule), "vkCreateShaderModule");
                return pModule.get(0);
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static String readResourceAsString(String classpathResource) {
        try (InputStream in = VulkanRenderer.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IOException("recurso não encontrado no jar: " + classpathResource);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new VulkanUnavailableException("falha ao ler " + classpathResource, e);
        }
    }

    // ── texturas das telas ──────────────────────────────────────────────────────────────────

    private ScreenTexture createScreenTexture(Screen screen) {
        // RETRATO: largura=linhas do framebuffer (240), altura=colunas (400 ou 320) — a rotação
        // para paisagem é feita só no shader de apresentação (ver present.frag).
        ScreenTexture texture = new ScreenTexture(Screen.ROWS, screen.columns());
        try (MemoryStack stack = stackPush()) {
            long[] imageAndMemory = createImage(texture.width, texture.height, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            texture.image = imageAndMemory[0];
            texture.imageMemory = imageAndMemory[1];
            texture.imageView = createImageView(texture.image, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT);
            texture.geometryFramebuffer = createGeometryFramebuffer(texture);

            long sizeBytes = (long) texture.width * texture.height * 4;
            long[] bufferAndMemory = createBuffer(sizeBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            texture.stagingBuffer = bufferAndMemory[0];
            texture.stagingMemory = bufferAndMemory[1];
            PointerBuffer pData = stack.mallocPointer(1);
            check(vkMapMemory(device, texture.stagingMemory, 0, sizeBytes, 0, pData), "vkMapMemory (staging)");
            texture.stagingMapped = pData.getByteBuffer(0, (int) sizeBytes);

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer pSet = stack.mallocLong(1);
            check(vkAllocateDescriptorSets(device, allocInfo, pSet), "vkAllocateDescriptorSets");
            texture.descriptorSet = pSet.get(0);

            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(texture.imageView)
                    .sampler(sampler);
            // G4.2, achado real: sem descriptorCount(1) explícito a struct calloc'd fica com
            // count=0 — vkUpdateDescriptorSets vira um no-op silencioso (sem erro de API) e o
            // descriptor set nunca é escrito de verdade. As validation layers só acusam isso no
            // vkCmdDraw seguinte ("descriptor... never been updated"), não na própria chamada de
            // update — por isso passava despercebido sem `-Dn3dsemu.vulkan.validation=true`. Era
            // a causa raiz real da janela ficar 100% preta mesmo com o framebuffer do guest e o
            // upload de textura corretos (confirmado por instrumentação: milhares de
            // uploadPending/renderFrame reais antes do fix, ainda preto).
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(texture.descriptorSet)
                    .dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(imageInfo);
            vkUpdateDescriptorSets(device, write, null);
        }
        return texture;
    }

    private long[] createImage(int width, int height, int format, int tiling, int usage, int memoryProperties) {
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo createInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(tiling)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            createInfo.extent().set(width, height, 1);

            LongBuffer pImage = stack.mallocLong(1);
            check(vkCreateImage(device, createInfo, null, pImage), "vkCreateImage");
            long image = pImage.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device, image, requirements);
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(findMemoryType(requirements.memoryTypeBits(), memoryProperties, stack));
            LongBuffer pMemory = stack.mallocLong(1);
            check(vkAllocateMemory(device, allocInfo, null, pMemory), "vkAllocateMemory (image)");
            long memory = pMemory.get(0);
            vkBindImageMemory(device, image, memory, 0);
            return new long[]{image, memory};
        }
    }

    private long[] createBuffer(long size, int usage, int memoryProperties) {
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo createInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            check(vkCreateBuffer(device, createInfo, null, pBuffer), "vkCreateBuffer");
            long buffer = pBuffer.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, requirements);
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(findMemoryType(requirements.memoryTypeBits(), memoryProperties, stack));
            LongBuffer pMemory = stack.mallocLong(1);
            check(vkAllocateMemory(device, allocInfo, null, pMemory), "vkAllocateMemory (buffer)");
            long memory = pMemory.get(0);
            vkBindBufferMemory(device, buffer, memory, 0);
            return new long[]{buffer, memory};
        }
    }

    private int findMemoryType(int typeFilter, int properties, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties memProperties = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProperties);
        for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
            boolean typeAllowed = (typeFilter & (1 << i)) != 0;
            boolean hasProperties = (memProperties.memoryTypes(i).propertyFlags() & properties) == properties;
            if (typeAllowed && hasProperties) {
                return i;
            }
        }
        throw new VulkanUnavailableException("nenhum tipo de memória compatível encontrado");
    }

    private long createImageView(long image, int format, int aspectMask) {
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo createInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);
            createInfo.subresourceRange()
                    .aspectMask(aspectMask)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            check(vkCreateImageView(device, createInfo, null, pView), "vkCreateImageView");
            return pView.get(0);
        }
    }

    // ── comandos por quadro ─────────────────────────────────────────────────────────────────

    private void createCommandBuffers() {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(FRAMES_IN_FLIGHT);
            PointerBuffer pBuffers = stack.mallocPointer(FRAMES_IN_FLIGHT);
            check(vkAllocateCommandBuffers(device, allocInfo, pBuffers), "vkAllocateCommandBuffers");
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                commandBuffers[i] = new VkCommandBuffer(pBuffers.get(i), device);
            }
        }
    }

    private void createSyncObjects() {
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);
            LongBuffer pSemaphore = stack.mallocLong(1);
            LongBuffer pFence = stack.mallocLong(1);
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                check(vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore), "vkCreateSemaphore");
                imageAvailableSemaphores[i] = pSemaphore.get(0);
                check(vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore), "vkCreateSemaphore");
                renderFinishedSemaphores[i] = pSemaphore.get(0);
                check(vkCreateFence(device, fenceInfo, null, pFence), "vkCreateFence");
                inFlightFences[i] = pFence.get(0);
            }
        }
    }

    private void renderFrame() {
        try (MemoryStack stack = stackPush()) {
            vkWaitForFences(device, inFlightFences[currentFrame], true, Long.MAX_VALUE);
            runAndClear(frameCleanup[currentFrame]);

            IntBuffer pImageIndex = stack.mallocInt(1);
            int acquireResult = vkAcquireNextImageKHR(device, swapchain, Long.MAX_VALUE,
                    imageAvailableSemaphores[currentFrame], VK_NULL_HANDLE, pImageIndex);
            if (acquireResult == VK_ERROR_OUT_OF_DATE_KHR || framebufferResized) {
                recreateSwapchain();
                return;
            }
            if (acquireResult != VK_SUCCESS && acquireResult != VK_SUBOPTIMAL_KHR) {
                check(acquireResult, "vkAcquireNextImageKHR");
            }
            int imageIndex = pImageIndex.get(0);

            vkResetFences(device, inFlightFences[currentFrame]);

            VkCommandBuffer commandBuffer = commandBuffers[currentFrame];
            vkResetCommandBuffer(commandBuffer, 0);
            recordCommandBuffer(commandBuffer, imageIndex, stack);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(imageAvailableSemaphores[currentFrame]))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(commandBuffer))
                    .pSignalSemaphores(stack.longs(renderFinishedSemaphores[currentFrame]));
            check(vkQueueSubmit(graphicsQueue, submitInfo, inFlightFences[currentFrame]), "vkQueueSubmit");

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(renderFinishedSemaphores[currentFrame]))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(swapchain))
                    .pImageIndices(stack.ints(imageIndex));
            int presentResult = vkQueuePresentKHR(presentQueue, presentInfo);
            if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR || framebufferResized) {
                recreateSwapchain();
            } else {
                check(presentResult, "vkQueuePresentKHR");
            }

            currentFrame = (currentFrame + 1) % FRAMES_IN_FLIGHT;
        }
    }

    private void recordCommandBuffer(VkCommandBuffer commandBuffer, int imageIndex, MemoryStack stack) {
        VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
        check(vkBeginCommandBuffer(commandBuffer, beginInfo), "vkBeginCommandBuffer");

        for (ScreenTexture texture : textures.values()) {
            if (texture.pendingRgba8 != null) {
                uploadPending(commandBuffer, texture, stack);
            }
        }
        drawPendingTriangles(commandBuffer, stack);

        VkClearValue.Buffer clearValues = VkClearValue.calloc(1, stack);
        clearValues.get(0).color().float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 1f);
        VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                .renderPass(renderPass)
                .framebuffer(swapchainFramebuffers[imageIndex])
                .pClearValues(clearValues);
        renderPassInfo.renderArea().offset().set(0, 0);
        renderPassInfo.renderArea().extent(swapchainExtent);

        vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline);

        drawScreen(commandBuffer, Screen.TOP, topScreenNdcRect(stack), stack);
        drawScreen(commandBuffer, Screen.BOTTOM, bottomScreenNdcRect(stack), stack);

        vkCmdEndRenderPass(commandBuffer);
        check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
    }

    /// RFC/task: "as duas telas empilhadas verticalmente (a inferior centrada... 400 de largura
    /// no topo, 320 embaixo)". Metade de cima da janela = tela TOP (largura cheia); metade de
    /// baixo = tela BOTTOM, centralizada horizontalmente (320/400 da largura).
    private java.nio.FloatBuffer topScreenNdcRect(MemoryStack stack) {
        return stack.floats(-1f, -1f, 1f, 0f);
    }

    private java.nio.FloatBuffer bottomScreenNdcRect(MemoryStack stack) {
        float inset = 1f - (float) Screen.BOTTOM.columns() / Screen.TOP.columns();
        return stack.floats(-1f + inset, 0f, 1f - inset, 1f);
    }

    private void drawScreen(VkCommandBuffer commandBuffer, Screen screen, java.nio.FloatBuffer rect, MemoryStack stack) {
        ScreenTexture texture = textures.get(screen);
        vkCmdPushConstants(commandBuffer, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, rect);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout,
                0, stack.longs(texture.descriptorSet), null);
        vkCmdDraw(commandBuffer, QUAD_VERTEX_COUNT, 1, 0, 0);
    }

    private void uploadPending(VkCommandBuffer commandBuffer, ScreenTexture texture, MemoryStack stack) {
        texture.stagingMapped.clear();
        texture.stagingMapped.put(texture.pendingRgba8);
        texture.stagingMapped.clear();
        texture.pendingRgba8 = null;

        int oldLayout = texture.everUploaded ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED;
        transitionImageLayout(commandBuffer, texture.image, oldLayout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, stack);

        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.imageOffset().set(0, 0, 0);
        region.imageExtent().set(texture.width, texture.height, 1);
        vkCmdCopyBufferToImage(commandBuffer, texture.stagingBuffer, texture.image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

        transitionImageLayout(commandBuffer, texture.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, stack);
        texture.everUploaded = true;
    }

    private void transitionImageLayout(VkCommandBuffer commandBuffer, long image, int oldLayout, int newLayout,
                                        MemoryStack stack) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

        int srcStage;
        int dstStage;
        if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            barrier.srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        } else if (oldLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            barrier.srcAccessMask(VK_ACCESS_SHADER_READ_BIT).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        } else {
            throw new IllegalStateException("transição de layout não suportada: " + oldLayout + " -> " + newLayout);
        }
        vkCmdPipelineBarrier(commandBuffer, srcStage, dstStage, 0, null, null, barrier);
    }

    @SuppressWarnings("unchecked")
    private static List<Runnable>[] newFrameCleanupArray() {
        List<Runnable>[] array = new List[FRAMES_IN_FLIGHT];
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            array[i] = new ArrayList<>();
        }
        return array;
    }

    private static void runAndClear(List<Runnable> tasks) {
        for (Runnable task : tasks) {
            task.run();
        }
        tasks.clear();
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new VulkanUnavailableException(operation + " falhou: VkResult=" + result);
        }
    }
}
