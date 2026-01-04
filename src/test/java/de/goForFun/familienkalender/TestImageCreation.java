package de.goForFun.familienkalender;

import org.junit.jupiter.api.Test;

import java.io.IOException;

public class TestImageCreation {

    @Test
    public void testImageCreation() throws IOException {
        CreateKalenderImage createKalenderImage = new CreateKalenderImage();
        createKalenderImage.createImage();
    }
}
