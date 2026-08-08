package me.eigenraven.lwjgl3ify.relauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/** Guards the Java 8 bootstrap path against APIs absent from Minecraft 1.7.10's Gson 2.2.4. */
public class RelauncherLegacyGsonCompatibilityTest {

    @Test
    public void configBytecodeDoesNotReferenceModernStaticJsonParserApi() throws Exception {
        String classResource = "/" + RelauncherConfig.class.getName()
            .replace('.', '/') + ".class";
        InputStream input = RelauncherConfig.class.getResourceAsStream(classResource);
        assertNotNull(classResource, input);

        byte[] classBytes;
        try {
            classBytes = readAll(input);
        } finally {
            input.close();
        }

        String constantPool = new String(classBytes, StandardCharsets.ISO_8859_1);
        assertFalse("Java 8 bootstrap must not call JsonParser.parseString", constantPool.contains("parseString"));
        assertTrue("Legacy JsonParser.parse call should remain present", constantPool.contains("parse"));
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
