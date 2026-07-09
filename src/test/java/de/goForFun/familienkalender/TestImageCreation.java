package de.goForFun.familienkalender;

import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class TestImageCreation {

    @Test
    public void testImageCreation() throws IOException {
        CreateKalenderImage createKalenderImage = new CreateKalenderImage();
        OutputStream outputStream = new FileOutputStream("test.png");
        createKalenderImage.createImage(outputStream);
    }
}
