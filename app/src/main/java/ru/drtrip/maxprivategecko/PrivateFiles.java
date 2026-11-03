package ru.drtrip.maxprivategecko;

import java.io.File;
import java.io.IOException;

/** Refuses traversal/symlinks outside the exact private transfer-cache subtrees. */
final class PrivateFiles {
    static final long MAX_AGE_MS = 60L * 60 * 1000;
    static boolean allowed(File cacheRoot, File file) throws IOException {
        String cache = cacheRoot.getCanonicalPath();
        String target = file.getCanonicalPath();
        for (String name : new String[]{"max_selected_files", "max_private_downloads"}) {
            String root = cache + File.separator + name;
            if (target.equals(root) || target.startsWith(root + File.separator)) return true;
        }
        return false;
    }
    static void deleteWithin(File cacheRoot, File file) throws IOException {
        if (!allowed(cacheRoot, file)) throw new IOException("Refused deletion outside private transfer cache");
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot list private cache");
            for (File child : children) deleteWithin(cacheRoot, child);
        }
        if (file.exists() && !file.delete()) throw new IOException("Cannot remove private cache");
    }
    static void purge(File cacheRoot) throws IOException {
        for (String name : new String[]{"max_selected_files", "max_private_downloads"}) {
            File folder = new File(cacheRoot, name);
            if (folder.exists()) deleteWithin(cacheRoot, folder);
        }
    }
    static void purgeExpired(File cacheRoot, long now) throws IOException {
        for (String name : new String[]{"max_selected_files", "max_private_downloads"}) {
            File folder = new File(cacheRoot, name);
            File[] files = folder.listFiles();
            if (files == null) continue;
            for (File file : files) if (now - file.lastModified() >= MAX_AGE_MS) deleteWithin(cacheRoot, file);
        }
    }
}
