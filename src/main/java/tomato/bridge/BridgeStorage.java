package tomato.bridge;

import com.google.gson.Gson;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Blocking storage boundary. Called only by the service's configuration or delivery worker. */
class BridgeStorage {
    BridgeConfig loadSettings(Path settings) throws IOException {return BridgeConfig.load(settings);}
    BridgeCatalog loadCatalog(BridgeConfig config) throws IOException {return BridgeCatalog.load(Paths.get(config.csvPath));}
    void save(BridgeConfig config,Path settings) throws IOException {config.save(settings);}

    Closeable acquireLock(Path settings) throws IOException {
        Path path=settings.toAbsolutePath().resolveSibling(".runtime").resolve("bridge.lock");
        Files.createDirectories(path.getParent());
        FileChannel channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try {lock=channel.tryLock();}catch(OverlappingFileLockException e){lock=null;}
            if(lock==null)throw new IOException("Another instance in this folder already owns the bridge. Close it before enabling here.");
            FileLock acquired=lock;
            return ()->{try{acquired.release();}finally{channel.close();}};
        } catch(IOException|RuntimeException e) {
            try{channel.close();}catch(IOException cleanup){e.addSuppressed(cleanup);}
            throw e;
        }
    }

    void audit(BridgeService.Review entry,BridgeConfig config) throws IOException {
        Path path=Paths.get(config.reviewLog).toAbsolutePath();Files.createDirectories(path.getParent());
        if(Files.exists(path)&&Files.size(path)>5*1024*1024)Files.move(path,path.resolveSibling(path.getFileName()+".1"),StandardCopyOption.REPLACE_EXISTING);
        String json=new Gson().toJson(entry)+System.lineSeparator();
        Files.write(path,json.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
}
