package vn.vnpt.util.common;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
public class QRCodeUtil {

    public static String generateQRCodeBase64(String content) throws Exception {
        return Base64.getEncoder().encodeToString(generateQRCodeBytes(content));
    }

    public static byte[] generateQRCodeBytes(String content) throws Exception {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        int width = 200;
        int height = 200;
        BitMatrix bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, width, height, hints);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        //Màu nền
        // Trắng
        int backgroundColor = -1;
        //Màu chữ
        // Đen
        int foregroundColor = -16777216;
        MatrixToImageConfig config = new MatrixToImageConfig(foregroundColor, backgroundColor);
        MatrixToImageWriter.writeToStream(bitMatrix, "png", byteArrayOutputStream, config);
        return byteArrayOutputStream.toByteArray();
    }
}
