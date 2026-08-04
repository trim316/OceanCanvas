package io.github.trim316.oceancanvas.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OceanCanvasSettingsTest {
    @Test
    void defaultsProduceTwentyThousandBlockOcean() {
        OceanCanvasSettings settings = OceanCanvasSettings.defaults();

        assertEquals(20_000L, settings.fullOceanWidth());
        assertEquals(22_000L, settings.fullGeneratedWidth());
    }

    @Test
    void squareBoundaryClassificationIsDeterministic() {
        OceanCanvasSettings settings = OceanCanvasSettings.defaults();

        assertEquals(OceanCanvasSettings.RegionBand.OCEAN, settings.classify(10_000, -10_000));
        assertEquals(OceanCanvasSettings.RegionBand.TRANSITION, settings.classify(10_001, 0));
        assertEquals(OceanCanvasSettings.RegionBand.TRANSITION, settings.classify(11_000, 11_000));
        assertEquals(OceanCanvasSettings.RegionBand.VANILLA, settings.classify(11_001, 0));
    }

    @Test
    void disabledSettingsAlwaysClassifyAsVanilla() {
        OceanCanvasSettings settings = new OceanCanvasSettings(false, 0, 0, 10_000, 1_000);

        assertEquals(OceanCanvasSettings.RegionBand.VANILLA, settings.classify(0, 0));
    }

    @Test
    void invalidDimensionsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new OceanCanvasSettings(true, 0, 0, 127, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new OceanCanvasSettings(true, 0, 0, 10_000, -1));
    }
}
