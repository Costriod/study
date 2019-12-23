```java
@Slf4j
public class RSAUtils {
    private static KeyPairGenerator keyPairGenerator;
    private static KeyPair keyPair;
    private static PublicKey publicKey;
    private static PrivateKey privateKey;
    private static KeyFactory keyFactory;

    static {
        try {
            keyFactory = KeyFactory.getInstance("RSA");
            keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            // keyPairGenerator.initialize(1024, new SecureRandom());
            keyPairGenerator.initialize(1024);//key至少是512位，否则报错
            keyPair = keyPairGenerator.genKeyPair();
            publicKey = keyPair.getPublic();
            privateKey = keyPair.getPrivate();
        } catch (NoSuchAlgorithmException e) {
            log.error("init rsa infrastructure error!", e);
        }
    }

    //将Base64编码后的公钥转换成PublicKey对象
    public static PublicKey stringToPublicKey(String publicKeyStr) throws Exception{
        byte[] keyBytes = Base64Utils.encode(publicKeyStr.getBytes());
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        PublicKey publicKey = keyFactory.generatePublic(keySpec);
        return publicKey;
    }

    //将Base64编码后的私钥转换成PrivateKey对象
    public static PrivateKey stringToPrivateKey(String privateKeyStr) throws Exception{
        byte[] keyBytes = Base64Utils.encode(privateKeyStr.getBytes());
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        PrivateKey privateKey = keyFactory.generatePrivate(keySpec);
        return privateKey;
    }

    //公钥加密
    public static byte[] encrypt(byte[] content, PublicKey publicKey) throws Exception{
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] bytes = cipher.doFinal(content);
        return bytes;
    }

    //私钥解密
    public static byte[] decrypt(byte[] content, PrivateKey privateKey) throws Exception{
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] bytes = cipher.doFinal(content);
        return bytes;
    }

    //私钥加密
    public static byte[] encrypt(byte[] content, PrivateKey privateKey) throws Exception{
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.ENCRYPT_MODE, privateKey);
        byte[] bytes = cipher.doFinal(content);
        return bytes;
    }

    //公钥解密
    public static byte[] decrypt(byte[] content, PublicKey publicKey) throws Exception{
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.DECRYPT_MODE, publicKey);
        byte[] bytes = cipher.doFinal(content);
        return bytes;
    }

    public static void main(String[] args) throws Exception {
        String content = "这是 this is 密文「」👿😭😄";
        System.out.println("公钥加密，私钥解密：");
        byte[] pubEncrypted = encrypt(content.getBytes(), publicKey);
        System.out.println(new String(Base64Utils.encode(pubEncrypted)));
        byte[] priDecrypted = decrypt(pubEncrypted, privateKey);
        System.out.println(new String(priDecrypted));

        System.out.println("私钥加密，公钥解密：");
        byte[] priEncrypted = encrypt(content.getBytes(), privateKey);
        System.out.println(new String(Base64Utils.encode(priEncrypted)));
        byte[] pubDecrypted = decrypt(priEncrypted, publicKey);
        System.out.println(new String(pubDecrypted));
    }
}
```
