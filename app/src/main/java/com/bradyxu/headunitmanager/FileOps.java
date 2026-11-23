package com.bradyxu.headunitmanager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

final class FileOps {
    private FileOps() { }

    static File safeChild(File parent, String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") ||
            name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0) return null;
        return new File(parent, name);
    }

    static boolean isSymlink(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent == null) return false;
        File inCanonicalParent = new File(parent.getCanonicalFile(), file.getName());
        return !inCanonicalParent.getCanonicalFile().equals(inCanonicalParent.getAbsoluteFile());
    }

    static boolean isInside(File child, File parent) throws IOException {
        String childPath = child.getCanonicalPath();
        String parentPath = parent.getCanonicalPath();
        String prefix = parentPath.endsWith(File.separator) ? parentPath : parentPath + File.separator;
        return childPath.equals(parentPath) || childPath.startsWith(prefix);
    }

    static void copy(File from, File to) throws IOException {
        if (isSymlink(from)) throw new IOException("不复制符号链接：" + from);
        if (!from.exists()) throw new IOException("源文件不存在：" + from);
        boolean created = false;
        try {
            if (from.isDirectory()) {
                if (isInside(to, from)) throw new IOException("不能复制到源目录内部");
                if (!to.mkdir()) throw new IOException("目标已存在或无法创建：" + to);
                created = true;
                File[] children = from.listFiles();
                if (children == null) throw new IOException("无法读取：" + from);
                for (File child : children) copy(child, new File(to, child.getName()));
            } else {
                if (!to.createNewFile()) throw new IOException("目标已存在：" + to);
                created = true;
                try (FileInputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
            }
        } catch (IOException e) {
            if (created && !delete(to)) throw new IOException(e.getMessage() + "；未能清理不完整目标 " + to, e);
            throw e;
        }
    }

    static boolean delete(File file) {
        try {
            if (isSymlink(file)) return file.delete();
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children == null) return false;
                for (File child : children) if (!delete(child)) return false;
            }
            return file.delete();
        } catch (IOException e) { return false; }
    }
}
