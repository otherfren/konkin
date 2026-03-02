package io.konkin.crypto.bitcoin;

import com.github.jleskovar.btcrpc.BitcoinRpcClient;
import com.github.jleskovar.btcrpc.BitcoinRpcClientFactory;

import javax.net.ssl.SSLContext;
import java.security.NoSuchAlgorithmException;

public class BitcoinTestnetTest {

    public static void main(String[] args) throws NoSuchAlgorithmException {
        BitcoinRpcClient client = BitcoinRpcClientFactory.createClient("testuser", "testpassword", "127.0.0.1", 9997, false, SSLContext.getDefault());
        System.out.println("Block count: " + client.getBlockCount());
    }
}
