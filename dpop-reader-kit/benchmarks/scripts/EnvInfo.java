import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;

/** Fournisseurs JCA effectivement choisis pour les opérations DPoP (java EnvInfo.java). */
public class EnvInfo {
    public static void main(String[] a) throws Exception {
        System.out.printf("{\"java.vm\":\"%s %s\",\"SHA256withECDSA\":\"%s\",\"EC.KeyPairGenerator\":\"%s\",\"SHA-256\":\"%s\",\"RSA-OAEP\":\"%s\"}%n",
            System.getProperty("java.vm.name"), System.getProperty("java.vm.version"),
            Signature.getInstance("SHA256withECDSA").getProvider(),
            KeyPairGenerator.getInstance("EC").getProvider(),
            MessageDigest.getInstance("SHA-256").getProvider(),
            javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").getProvider());
    }
}
