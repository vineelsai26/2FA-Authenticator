package com.vs.authenticator;

import android.net.Uri;

import androidx.annotation.NonNull;

import com.google.android.apps.authenticator.Base32String;
import com.google.android.apps.authenticator.Base32String.DecodingException;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class Token {
    private static final char[] STEAM_CHARS = new char[]{
            '2', '3', '4', '5', '6', '7', '8', '9', 'B', 'C',
            'D', 'F', 'G', 'H', 'J', 'K', 'M', 'N', 'P', 'Q',
            'R', 'T', 'V', 'W', 'X', 'Y'};
    private final String issuerInt;
    private final String issuerExt;
    private final String label;
    private final byte[] secret;
    private final int digits;
    private String issuerAlt;
    private String labelAlt;
    private TokenType type;
    private String algo;
    private long counter;
    private int period;

    private Token(Uri uri, boolean internal) throws TokenUriInvalidException {
        validateTokenURI(uri);

        String path = uri.getPath();
        // Strip the path of its leading '/'
        path = path.replaceFirst("/", "");

        if (path.length() == 0)
            throw new TokenUriInvalidException();

        int i = path.indexOf(':');
        issuerExt = i < 0 ? "" : path.substring(0, i);
        issuerInt = uri.getQueryParameter("issuer");
        label = path.substring(i >= 0 ? i + 1 : 0);

        algo = uri.getQueryParameter("algorithm");
        if (algo == null)
            algo = "sha1";
        algo = algo.toUpperCase(Locale.US);
        try {
            Mac.getInstance("Hmac" + algo);
        } catch (NoSuchAlgorithmException e1) {
            throw new TokenUriInvalidException();
        }

        try {
            String d = uri.getQueryParameter("digits");
            if (d == null)
                d = "6";
            digits = Integer.parseInt(d);
            if (!issuerExt.equals("Steam") && digits != 6 && digits != 8)
                throw new TokenUriInvalidException();
        } catch (NumberFormatException e) {
            throw new TokenUriInvalidException();
        }

        try {
            String p = uri.getQueryParameter("period");
            if (p == null)
                p = "30";
            period = Integer.parseInt(p);
            period = (period > 0) ? period : 30; // Avoid divide-by-zero
        } catch (NumberFormatException e) {
            throw new TokenUriInvalidException();
        }

        if (type == TokenType.HOTP) {
            try {
                String c = uri.getQueryParameter("counter");
                if (c == null)
                    c = "0";
                counter = Long.parseLong(c);
            } catch (NumberFormatException e) {
                throw new TokenUriInvalidException();
            }
        }

        try {
            String s = uri.getQueryParameter("secret");
            secret = Base32String.decode(s);
        } catch (DecodingException | NullPointerException e) {
            throw new TokenUriInvalidException();
        }

        if (internal) {
            setIssuer(uri.getQueryParameter("issueralt"));
            setLabel(uri.getQueryParameter("labelalt"));
        }
    }

    public Token(String uri, boolean internal) throws TokenUriInvalidException {
        this(Uri.parse(uri), internal);
    }

    public Token(Uri uri) throws TokenUriInvalidException {
        this(uri, false);
    }

    public Token(String uri) throws TokenUriInvalidException {
        this(Uri.parse(uri));
    }

    private void validateTokenURI(Uri uri) throws TokenUriInvalidException {
        if (uri == null) throw new TokenUriInvalidException();

        if (uri.getScheme() == null || !uri.getScheme().equals("otpauth")) {
            throw new TokenUriInvalidException();
        }

        if (uri.getAuthority() == null) throw new TokenUriInvalidException();

        if (uri.getAuthority().equals("totp")) {
            type = TokenType.TOTP;
        } else if (uri.getAuthority().equals("hotp"))
            type = TokenType.HOTP;
        else {
            throw new TokenUriInvalidException();
        }

        if (uri.getPath() == null) throw new TokenUriInvalidException();
    }

    private String getHOTP(long counter) {
        // Encode counter in network byte order
        ByteBuffer bb = ByteBuffer.allocate(8);
        bb.putLong(counter);

        // Create digits divisor
        int div = 1;
        for (int i = digits; i > 0; i--)
            div *= 10;

        // Create the HMAC
        try {
            Mac mac = Mac.getInstance("Hmac" + algo);
            mac.init(new SecretKeySpec(secret, "Hmac" + algo));

            // Do the hashing
            byte[] digest = mac.doFinal(bb.array());

            // Truncate
            int binary;
            int off = digest[digest.length - 1] & 0xf;
            binary = (digest[off] & 0x7f) << 0x18;
            binary |= (digest[off + 1] & 0xff) << 0x10;
            binary |= (digest[off + 2] & 0xff) << 0x08;
            binary |= (digest[off + 3] & 0xff);

            StringBuilder hotp = new StringBuilder();
            if (issuerExt.equals("Steam")) {
                for (int i = 0; i < digits; i++) {
                    hotp.append(STEAM_CHARS[binary % STEAM_CHARS.length]);
                    binary /= STEAM_CHARS.length;
                }
            } else {
                binary = binary % div;

                // Zero pad
                hotp = new StringBuilder(Integer.toString(binary));
                while (hotp.length() != digits)
                    hotp.insert(0, "0");
            }

            return hotp.toString();
        } catch (InvalidKeyException | NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        return "";
    }

    public String getID() {
        String id;
        if (issuerInt != null && !issuerInt.equals(""))
            id = issuerInt + ":" + label;
        else if (issuerExt != null && !issuerExt.equals(""))
            id = issuerExt + ":" + label;
        else
            id = label;

        return id;
    }

    public String getIssuer() {
        if (issuerAlt != null)
            return issuerAlt;
        return issuerExt != null ? issuerExt : "";
    }

    // NOTE: This changes internal data. You MUST save the token immediately.
    public void setIssuer(String issuer) {
        issuerAlt = (issuer == null || issuer.equals(this.issuerExt)) ? null : issuer;
    }

    public String getLabel() {
        if (labelAlt != null)
            return labelAlt;
        return label != null ? label : "";
    }

    // NOTE: This changes internal data. You MUST save the token immediately.
    public void setLabel(String label) {
        labelAlt = (label == null || label.equals(this.label)) ? null : label;
    }

    // NOTE: This may change internal data. You MUST save the token immediately.
    public TokenCode generateCodes() {
        long cur = System.currentTimeMillis();

        switch (type) {
            case HOTP:
                return new TokenCode(getHOTP(counter++), cur, cur + (period * 1000L));

            case TOTP:
                long counter = cur / 1000 / period;
                return new TokenCode(getHOTP(counter),
                        (counter) * period * 1000,
                        (counter + 1) * period * 1000,
                        new TokenCode(getHOTP(counter + 1),
                                (counter + 1) * period * 1000,
                                (counter + 2) * period * 1000));
        }

        return null;
    }

    public Uri toUri() {
        String issuerLabel = !issuerExt.equals("") ? issuerExt + ":" + label : label;

        Uri.Builder builder = new Uri.Builder().scheme("otpauth").path(issuerLabel)
                .appendQueryParameter("secret", Base32String.encode(secret))
                .appendQueryParameter("issuer", issuerInt == null ? issuerExt : issuerInt)
                .appendQueryParameter("algorithm", algo)
                .appendQueryParameter("digits", Integer.toString(digits))
                .appendQueryParameter("period", Integer.toString(period));

        switch (type) {
            case HOTP:
                builder.authority("hotp");
                builder.appendQueryParameter("counter", Long.toString(counter + 1));
                break;
            case TOTP:
                builder.authority("totp");
                break;
        }

        return builder.build();
    }

    @NonNull
    @Override
    public String toString() {
        return toUri().toString();
    }

    public enum TokenType {
        HOTP, TOTP
    }

    public static class TokenUriInvalidException extends Exception {
        private static final long serialVersionUID = -1108624734612362345L;
    }
}
