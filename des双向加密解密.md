```java
@Slf4j
public class DesUtils {
    private static SecretKey secretKeyDESede;
    private static SecretKey secretKeyDES;

    static {
        try {
//            //两种实现，第一种是手动采用DESedeKeySpec指定key，第二种是下面的KeyGenerator
//            DESedeKeySpec keySpec = new DESedeKeySpec("AAAAAAAAAAAAAAAAAAAAAAAA".getBytes());
//            SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("DESede");
//            secretKeyDESede = keyFactory.generateSecret(keySpec);
            
            KeyGenerator kg = KeyGenerator.getInstance("DESede");
            //如果是desede则KeyGenerator必须是112或168位key，否则抛异常
            kg.init(168);
            secretKeyDESede = kg.generateKey();

            kg = KeyGenerator.getInstance("DES");
            //如果是des则KeyGenerator必须是56位key，否则抛异常
            kg.init(56);
            secretKeyDES = kg.generateKey();
        } catch (Exception e) {
            log.error("init des infrastructure error!", e);
        }
    }

    /**
     * @param keyiv IV
     * @param data  明文
     * @return Base64编码的密文
     * @throws Exception
     * @Description CBC加密
     */
    public static byte[] encrypt(byte[] keyiv, byte[] data) throws Exception {
        //也可以手工输入字符串生成密钥 DESedeKeySpec secretKeyDESede = new DESedeKeySpec(secretBytes);
        Cipher cipher = Cipher.getInstance("desede/CBC/PKCS5Padding");
        IvParameterSpec ips = new IvParameterSpec(keyiv);
        //如果是desede则不需要ips向量，如果是desede/CBC/PKCS5Padding，就必须要ips向量，否则报错
        cipher.init(Cipher.ENCRYPT_MODE, secretKeyDESede, ips);
        return cipher.doFinal(data);
    }

    /**
     * @param keyiv IV
     * @param data  Base64编码的密文
     * @return 明文
     * @throws Exception
     * @Description CBC解密
     */
    public static byte[] decrypt(byte[] keyiv, byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("desede/CBC/PKCS5Padding");
        IvParameterSpec ips = new IvParameterSpec(keyiv);
        cipher.init(Cipher.DECRYPT_MODE, secretKeyDESede, ips);
        return cipher.doFinal(data);
    }

    public static byte[] encrypt(byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("DES");
        cipher.init(Cipher.ENCRYPT_MODE, secretKeyDES);
        return cipher.doFinal(data);
    }

    public static byte[] decrypt(byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("DES");
        cipher.init(Cipher.DECRYPT_MODE, secretKeyDES);
        return cipher.doFinal(data);
    }

    public static void main(String[] args) throws Exception {
        //向量必须是8字节
        byte[] keyiv = {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] data = "张三「」".getBytes();

        System.out.println("desede加密解密");
        byte[] encrypted = encrypt(keyiv, data);
        byte[] decrypted = decrypt(keyiv, encrypted);
        System.out.println("密文：" + new String(Base64Utils.encode(encrypted)));
        System.out.println("明文：" + new String(decrypted));


        System.out.println("des加密解密");
        encrypted = encrypt(data);
        decrypted = decrypt(encrypted);
        System.out.println("密文：" + new String(Base64Utils.encode(encrypted)));
        System.out.println("明文：" + new String(decrypted));
    }
}
```
