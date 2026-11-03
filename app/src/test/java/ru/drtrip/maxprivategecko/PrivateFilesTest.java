package ru.drtrip.maxprivategecko;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class PrivateFilesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void purgeRemovesOnlyTransferTrees() throws Exception {
        File cache = temp.newFolder("cache");
        File transfer = new File(cache,"max_selected_files/request/item.jpg");
        assertTrue(transfer.getParentFile().mkdirs());
        Files.write(transfer.toPath(),new byte[]{1,2,3});
        File unrelated = new File(cache,"keep.bin");
        Files.write(unrelated.toPath(),new byte[]{9});
        PrivateFiles.purge(cache);
        assertFalse(transfer.exists()); assertTrue(unrelated.exists());
    }
    @Test public void refusesTraversalAndPrefixConfusion() throws Exception {
        File cache = temp.newFolder("cache");
        assertFalse(PrivateFiles.allowed(cache,new File(cache,"max_selected_files/../../outside")));
        assertFalse(PrivateFiles.allowed(cache,new File(cache,"max_selected_files-other/data")));
        assertFalse(PrivateFiles.allowed(cache,cache));
    }
    @Test public void symlinkCannotPurgeExternalTarget() throws Exception {
        File cache = temp.newFolder("cache"); File outside = temp.newFolder("outside");
        File keep = new File(outside,"keep.txt"); Files.write(keep.toPath(),new byte[]{7});
        File folder = new File(cache,"max_selected_files"); assertTrue(folder.mkdir());
        File link = new File(folder,"linked"); Files.createSymbolicLink(link.toPath(),outside.toPath());
        try { PrivateFiles.deleteWithin(cache,link); fail("must refuse external target"); }
        catch(IOException expected) { assertTrue(keep.exists()); }
    }
    @Test public void expiryKeepsRecentCopies() throws Exception {
        File cache=temp.newFolder("cache"), folder=new File(cache,"max_private_downloads");
        assertTrue(folder.mkdir());
        File old=new File(folder,"old.tmp"), recent=new File(folder,"recent.tmp");
        Files.write(old.toPath(),new byte[]{1}); Files.write(recent.toPath(),new byte[]{2});
        long now=System.currentTimeMillis(); assertTrue(old.setLastModified(now-PrivateFiles.MAX_AGE_MS-1000));
        PrivateFiles.purgeExpired(cache,now);
        assertFalse(old.exists()); assertTrue(recent.exists());
    }
}
