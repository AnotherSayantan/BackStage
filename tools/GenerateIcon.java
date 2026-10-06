import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.*;
import java.nio.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

public class GenerateIcon {
    public static void main(String[] args) throws Exception {
        File outDir = new File("composeApp/src/desktopMain/resources");
        outDir.mkdirs();

        File pngFile = new File(outDir, "icon.png");
        File icoFile = new File(outDir, "icon.ico");

        // 1. Generate 512x512 high-res master PNG
        BufferedImage img512 = renderBackStageIcon(512);
        ImageIO.write(img512, "PNG", pngFile);
        System.out.println("Written master PNG: " + pngFile.getAbsolutePath() + " (" + pngFile.length() + " bytes)");

        // 2. Generate multi-resolution images for ICO
        int[] sizes = {16, 32, 48, 64, 128, 256};
        List<byte[]> payloads = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();

        for (int sz : sizes) {
            BufferedImage img = renderBackStageIcon(sz);
            widths.add(sz == 256 ? 0 : sz);
            heights.add(sz == 256 ? 0 : sz);

            if (sz == 256) {
                // Use PNG for 256x256
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "PNG", baos);
                payloads.add(baos.toByteArray());
            } else {
                // Use 32-bit DIB for <= 128
                payloads.add(createDibPayload(img));
            }
        }

        // Build ICO file
        ByteArrayOutputStream icoBaos = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0); // reserved
        header.putShort((short) 1); // type 1 = icon
        header.putShort((short) sizes.length); // count
        icoBaos.write(header.array());

        int dirSize = 6 + sizes.length * 16;
        int currentOffset = dirSize;

        for (int i = 0; i < sizes.length; i++) {
            byte[] payload = payloads.get(i);
            ByteBuffer entry = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            entry.put((byte) widths.get(i).intValue());
            entry.put((byte) heights.get(i).intValue());
            entry.put((byte) 0); // colors
            entry.put((byte) 0); // reserved
            entry.putShort((short) 1); // planes
            entry.putShort((short) 32); // bit count
            entry.putInt(payload.length);
            entry.putInt(currentOffset);
            icoBaos.write(entry.array());
            currentOffset += payload.length;
        }

        for (byte[] payload : payloads) {
            icoBaos.write(payload);
        }

        try (FileOutputStream fos = new FileOutputStream(icoFile)) {
            fos.write(icoBaos.toByteArray());
        }
        System.out.println("Written multi-res ICO: " + icoFile.getAbsolutePath() + " (" + icoFile.length() + " bytes)");
    }

    private static BufferedImage renderBackStageIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);

        float s = size;

        // 1. Drop shadow beneath the tile
        float shadowOffset = s * 0.025f;
        float shadowPad = s * 0.07f;
        float tileX = shadowPad;
        float tileY = shadowPad;
        float tileW = s - 2 * shadowPad;
        float tileH = s - 2 * shadowPad;
        float tileCorner = tileW * 0.22f;

        // Soft shadow layers
        for (int i = 3; i >= 1; i--) {
            float blurOffset = shadowOffset + i * (s * 0.012f);
            int shadowAlpha = Math.max(10, 45 / i);
            g2.setColor(new Color(0, 0, 0, shadowAlpha));
            g2.fill(new RoundRectangle2D.Float(
                tileX - i * (s * 0.005f),
                tileY + blurOffset,
                tileW + 2 * i * (s * 0.005f),
                tileH,
                tileCorner + i * 2,
                tileCorner + i * 2
            ));
        }

        // 2. Sleek dark squircle tile background (gradient)
        GradientPaint bgGradient = new GradientPaint(
            tileX, tileY, new Color(0x1D, 0x1D, 0x22),
            tileX, tileY + tileH, new Color(0x0C, 0x0C, 0x0E)
        );
        g2.setPaint(bgGradient);
        RoundRectangle2D.Float tileShape = new RoundRectangle2D.Float(tileX, tileY, tileW, tileH, tileCorner, tileCorner);
        g2.fill(tileShape);

        // 3. Subtle outer rim highlight on the tile
        g2.setColor(new Color(0x32, 0x32, 0x3A, 0xAA));
        g2.setStroke(new BasicStroke(Math.max(1.0f, s * 0.005f)));
        g2.draw(tileShape);

        // 4. BackStage Emblem in anodized brass / amber
        // Proportions centered within the tile
        float emblemW = tileW * 0.60f;
        float emblemH = tileH * 0.60f;
        float emblemX = tileX + (tileW - emblemW) / 2.0f;
        float emblemY = tileY + (tileH - emblemH) / 2.0f;

        // Gold/brass metallic gradient
        GradientPaint goldGradient = new GradientPaint(
            emblemX, emblemY, new Color(0xF5, 0xBE, 0x4D), // #F5BE4D
            emblemX, emblemY + emblemH, new Color(0xD2, 0x93, 0x26) // #D29326
        );
        g2.setPaint(goldGradient);

        // Outer architectural frame
        float strokeWidth = emblemW * 0.12f;
        float outerCorner = emblemW * 0.18f;
        float halfStroke = strokeWidth / 2.0f;

        RoundRectangle2D.Float outerFrame = new RoundRectangle2D.Float(
            emblemX + halfStroke,
            emblemY + halfStroke,
            emblemW - strokeWidth,
            emblemH - strokeWidth,
            outerCorner,
            outerCorner
        );
        g2.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(outerFrame);

        // Inner solid vault square
        float innerSize = emblemW * 0.44f;
        float innerCorner = innerSize * 0.22f;
        float innerX = emblemX + (emblemW - innerSize) / 2.0f;
        float innerY = emblemY + (emblemH - innerSize) / 2.0f;

        RoundRectangle2D.Float innerVault = new RoundRectangle2D.Float(
            innerX,
            innerY,
            innerSize,
            innerSize,
            innerCorner,
            innerCorner
        );
        g2.fill(innerVault);

        g2.dispose();
        return img;
    }

    private static byte[] createDibPayload(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();

        int andRowBytes = ((w + 31) / 32) * 4;
        int andMaskSize = andRowBytes * h;
        int xorSize = w * h * 4;
        int dibSize = 40 + xorSize + andMaskSize;

        ByteBuffer buf = ByteBuffer.allocate(dibSize).order(ByteOrder.LITTLE_ENDIAN);

        // BITMAPINFOHEADER (40 bytes)
        buf.putInt(40); // biSize
        buf.putInt(w);  // biWidth
        buf.putInt(h * 2); // biHeight (doubled for XOR + AND mask)
        buf.putShort((short) 1); // biPlanes
        buf.putShort((short) 32); // biBitCount
        buf.putInt(0); // biCompression = BI_RGB
        buf.putInt(xorSize + andMaskSize); // biSizeImage
        buf.putInt(0); // biXPelsPerMeter
        buf.putInt(0); // biYPelsPerMeter
        buf.putInt(0); // biClrUsed
        buf.putInt(0); // biClrImportant

        // Bottom-up 32-bit BGRA pixels
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                byte a = (byte) ((argb >> 24) & 0xFF);
                byte r = (byte) ((argb >> 16) & 0xFF);
                byte g = (byte) ((argb >> 8) & 0xFF);
                byte b = (byte) (argb & 0xFF);
                buf.put(b);
                buf.put(g);
                buf.put(r);
                buf.put(a);
            }
        }

        // Bottom-up 1-bit AND mask
        byte[] andRow = new byte[andRowBytes];
        for (int y = h - 1; y >= 0; y--) {
            Arrays.fill(andRow, (byte) 0);
            for (int x = 0; x < w; x++) {
                int a = (img.getRGB(x, y) >> 24) & 0xFF;
                if (a < 128) {
                    andRow[x / 8] |= (1 << (7 - (x % 8)));
                }
            }
            buf.put(andRow);
        }

        return buf.array();
    }
}
