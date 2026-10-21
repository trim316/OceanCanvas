package net.oceancanvas.mod.worldgen;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PressureProofCadenceTest {
    @Test void callersShareOneAttemptAndIndependentWorldsDoNotShareBudget() {
        var a=new OceanCanvasPressureProofCadence();var b=new OceanCanvasPressureProofCadence();
        for(long tick=0;tick<10000;tick++) {
            assertEquals(OceanCanvasPressureProofCadence.Lane.TICKET_NEIGHBORHOOD,a.claim(tick,false,true));
            for(int i=0;i<16;i++)assertEquals(OceanCanvasPressureProofCadence.Lane.NONE,a.claim(tick,false,true));
            assertEquals(OceanCanvasPressureProofCadence.Lane.TICKET_NEIGHBORHOOD,b.claim(tick,false,true));
        }
    }
    @Test void genericLaneRemainsTerrainDormantThenAlternatesWithoutDroppingDebt() {
        var a=new OceanCanvasPressureProofCadence();
        assertEquals(OceanCanvasPressureProofCadence.Lane.NONE,a.claim(0,false,false));
        assertEquals(OceanCanvasPressureProofCadence.Lane.TICKET_NEIGHBORHOOD,a.claim(1,true,true));
        assertEquals(OceanCanvasPressureProofCadence.Lane.GENERIC,a.claim(2,true,true));
        assertEquals(OceanCanvasPressureProofCadence.Lane.TICKET_NEIGHBORHOOD,a.claim(3,true,true));
        assertEquals(OceanCanvasPressureProofCadence.Lane.GENERIC,a.claim(4,true,false));
    }
}
