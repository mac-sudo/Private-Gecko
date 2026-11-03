package ru.drtrip.maxprivategecko;
import org.junit.Test;
import static org.junit.Assert.*;
public class ImageLimitsTest {
    @Test public void ordinaryPhotoKeepsResolution() { assertEquals(1,ImageLimits.sample(4032,3024,16000000)); }
    @Test public void largePhotoIsBounded() { assertEquals(2,ImageLimits.sample(8000,8000,16000000)); }
    @Test public void extremeAspectRatioCannotRoundToZero() { assertEquals(256,ImageLimits.sample(1,Integer.MAX_VALUE,16000000)); }
    @Test(expected=IllegalArgumentException.class) public void invalidHeaderIsRejected() { ImageLimits.sample(0,100,16000000); }
}
