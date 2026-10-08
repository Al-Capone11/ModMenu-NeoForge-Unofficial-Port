package com.terraformersmc.modmenu.util.mod.neoforge;

import com.mojang.blaze3d.platform.NativeImage;
import com.terraformersmc.modmenu.ModMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.modlist.ImageResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Loads mod icons from the combined resource manager so icons in mod jars are visible. */
public final class NeoForgeIconHandler implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Mod Menu | NeoForgeIconHandler");
    private final Map<String, DynamicTexture> cache = new HashMap<>();

    public DynamicTexture createIcon(String modId, String path) {
        String key = modId + ":" + path;
        if (cache.containsKey(key)) return cache.get(key);
        try {
            Identifier resourceId = Identifier.fromNamespaceAndPath(modId, path.startsWith("assets/")
                    ? path.substring(("assets/" + modId + "/").length()) : path);
            var resource = Minecraft.getInstance().getResourceManager().getResource(resourceId);
            if (resource.isEmpty()) return null;
            try (InputStream input = resource.get().open()) {
                NativeImage image = NativeImage.read(input);
                if (image.getWidth() != image.getHeight()) {
                    image.close();
                    LOGGER.warn("Mod icon must be square: {}", resourceId);
                    return null;
                }
                DynamicTexture texture = new DynamicTexture(() -> key, image);
                cache.put(key, texture);
                return texture;
            }
        } catch (Exception e) {
            LOGGER.debug("Unable to load mod icon {}", key, e);
            return null;
        }
    }

    public DynamicTexture createIcon(String modId, Iterable<ImageResource> imageResources) {
        for (ImageResource imageResource : imageResources) {
            DynamicTexture icon = createIcon(modId, imageResource);
            if (icon != null) return icon;
        }
        return null;
    }

    private DynamicTexture createIcon(String modId, ImageResource imageResource) {
        String key = modId + "#" + imageResource;
        if (cache.containsKey(key)) return cache.get(key);
        try {
            var imageSupplier = imageResource.get(Minecraft.getInstance().getResourceManager());
            if (imageSupplier == null) return null;
            try (InputStream input = imageSupplier.get()) {
                NativeImage nativeImage = NativeImage.read(input);
                if (nativeImage.getWidth() != nativeImage.getHeight()) {
                    int size = Math.min(nativeImage.getWidth(), nativeImage.getHeight());
                    int offsetX = (nativeImage.getWidth() - size) / 2;
                    int offsetY = (nativeImage.getHeight() - size) / 2;
                    NativeImage square = new NativeImage(size, size, true);
                    for (int y = 0; y < size; y++) {
                        for (int x = 0; x < size; x++) {
                            square.setPixel(x, y, nativeImage.getPixel(x + offsetX, y + offsetY));
                        }
                    }
                    nativeImage.close();
                    nativeImage = square;
                }
                DynamicTexture texture = new DynamicTexture(() -> key, nativeImage);
                cache.put(key, texture);
                return texture;
            }
        } catch (Exception e) {
            LOGGER.debug("Unable to load mod icon {}", key, e);
            return null;
        }
    }

    @Override public void close() {
        cache.values().forEach(DynamicTexture::close);
        cache.clear();
    }
}
