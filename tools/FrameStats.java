import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Frame classification for tools/pack-sweep.sh (run with {@code java tools/FrameStats.java <png>...}, no build needed).
 * Looks at the world area of a game-test screenshot (top 60 % of the height: excludes the hotbar, hearts and chat, which stay
 * visible on a black frame) and prints, per file:
 * <pre>file mean=0.412 stddev=0.183 dark=0.02 verdict=ok</pre>
 * mean/stddev are Rec. 709 relative luminance (sRGB values, 0..1); dark is the fraction of pixels with luminance
 * below 0.04. verdict: {@code black} (almost every pixel dark), {@code uniform} (almost no variation: a flat colour,
 * e.g. a cleared but never drawn target), {@code dark} (mostly dark but with some content, e.g. a night scene or
 * a missing lighting pass: look at it), {@code ok}.
 */
public final class FrameStats {
    private FrameStats() {
    }

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            BufferedImage image = ImageIO.read(new File(arg));
            if (image == null) {
                System.out.println(arg + " verdict=unreadable");
                continue;
            }
            System.out.println(arg + " " + stats(image));
        }
    }

    static String stats(BufferedImage image) {
        int w = image.getWidth();
        int h = (int) (image.getHeight() * 0.60);
        double sum = 0;
        double sumSq = 0;
        int dark = 0;
        int n = 0;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int rgb = image.getRGB(x, y);
                double l = (0.2126 * ((rgb >> 16) & 0xFF) + 0.7152 * ((rgb >> 8) & 0xFF) + 0.0722 * (rgb & 0xFF)) / 255.0;
                sum += l;
                sumSq += l * l;
                if (l < 0.04) {
                    dark++;
                }
                n++;
            }
        }
        double mean = sum / Math.max(1, n);
        double stddev = Math.sqrt(Math.max(0, sumSq / Math.max(1, n) - mean * mean));
        double darkFraction = (double) dark / Math.max(1, n);
        String verdict;
        if (darkFraction > 0.97) {
            verdict = "black";
        } else if (stddev < 0.015) {
            verdict = "uniform";
        } else if (darkFraction > 0.75) {
            verdict = "dark";
        } else {
            verdict = "ok";
        }
        // The crosshair (a few white pixels) is the only content of an otherwise black frame: dark > 0.97 tolerates it.
        return String.format(Locale.ROOT, "mean=%.3f stddev=%.3f dark=%.2f verdict=%s", mean, stddev, darkFraction, verdict);
    }
}
