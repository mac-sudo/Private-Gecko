package ru.drtrip.maxprivategecko;

/** Ceiling arithmetic matters for very narrow, untrusted image dimensions. */
final class ImageLimits {
    static int sample(int width, int height, long pixels) {
        if (width < 1 || height < 1 || pixels < 1) throw new IllegalArgumentException("Invalid image bounds");
        int sample = 1;
        while (((width + (long)sample - 1) / sample) * ((height + (long)sample - 1) / sample) > pixels) sample *= 2;
        return sample;
    }
}
