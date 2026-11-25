// SPDX-License-Identifier: GPL-2.0-or-later
package app.droidalter.camera;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.*;
import java.security.cert.X509Certificate;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.util.*;

/** Device-local TLS identity. The private key never leaves Android Keystore. */
final class PairingIdentity {
    final SSLContext tls;
    final String fingerprint;
    final String code;
    PairingIdentity(Context context) throws Exception {
        String alias="cambridge_wifi_identity_v4";
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
        // TLS computes its digest before asking Android Keystore to sign it.
        // Authorize DIGEST_NONE for that operation; TLS still negotiates SHA-256 or stronger.
        if(!keys.containsAlias(alias)) {
            KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE,KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1,KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setCertificateSubject(new X500Principal("CN=CamBridge Phone"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(new Date(System.currentTimeMillis()-86_400_000L))
                .setCertificateNotAfter(new Date(System.currentTimeMillis()+10L*365*86_400_000L)).build());
            generator.generateKeyPair();
        }
        X509Certificate certificate=(X509Certificate)keys.getCertificate(alias);
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());StringBuilder hex=new StringBuilder();
        for(byte b:digest){hex.append(String.format(Locale.US,"%02X",b));}fingerprint=hex.toString();
        // A fresh 128-bit secret for every Wi-Fi enable/session; no shared default password.
        byte[] secret=new byte[16];new SecureRandom().nextBytes(secret);StringBuilder token=new StringBuilder();
        for(byte b:secret){token.append(String.format(Locale.US,"%02x",b));}code=token.toString();
        final PrivateKey privateKey=(PrivateKey)keys.getKey(alias,null);
        final X509Certificate[] chain={certificate};
        X509ExtendedKeyManager manager=new X509ExtendedKeyManager(){
            private boolean rsa(String type){return type!=null&&(type.equals("RSA")||type.equals("RSASSA-PSS"));}
            public String[] getClientAliases(String type,Principal[] issuers){return null;}
            public String chooseClientAlias(String[] types,Principal[] issuers,java.net.Socket socket){return null;}
            public String[] getServerAliases(String type,Principal[] issuers){return rsa(type)?new String[]{"cambridge"}:null;}
            public String chooseServerAlias(String type,Principal[] issuers,java.net.Socket socket){return rsa(type)?"cambridge":null;}
            public String chooseEngineServerAlias(String type,Principal[] issuers,SSLEngine engine){return rsa(type)?"cambridge":null;}
            public X509Certificate[] getCertificateChain(String name){return name.equals("cambridge")?chain.clone():null;}
            public PrivateKey getPrivateKey(String name){return name.equals("cambridge")?privateKey:null;}
        };
        tls=SSLContext.getInstance("TLS");tls.init(new KeyManager[]{manager},null,new SecureRandom());
    }
}
