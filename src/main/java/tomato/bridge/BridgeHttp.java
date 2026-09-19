package tomato.bridge;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** No redirects and no automatic retries: the public receiver has no event idempotency key. */
public final class BridgeHttp implements BridgeService.Transport {
    @Override public BridgeService.Response post(String endpoint, String json) throws IOException {
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();
        try {
            c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setConnectTimeout(1500);c.setReadTimeout(2500);
            c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
            byte[] bytes=json.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out=c.getOutputStream()){out.write(bytes);}
            int status=c.getResponseCode();
            InputStream stream=status>=400?c.getErrorStream():c.getInputStream();
            ByteArrayOutputStream body=new ByteArrayOutputStream();
            if(stream!=null) try(InputStream in=stream) {
                byte[] buffer=new byte[1024];int n;
                while(body.size()<16384 && (n=in.read(buffer,0,Math.min(buffer.length,16384-body.size())))!=-1)body.write(buffer,0,n);
            }
            return new BridgeService.Response(status,new String(body.toByteArray(),StandardCharsets.UTF_8));
        } finally {c.disconnect();}
    }
}
