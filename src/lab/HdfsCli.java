package lab;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/** HDFS operations; generic Hadoop options such as -fs and -conf are supported. */
public final class HdfsCli extends Configured implements Tool {
    private FileSystem fs;
    private static final String CREATED = "user.lab.createdAt";
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
    }
    private void parents(Path p) throws IOException {
        if (p.getParent() != null && !fs.mkdirs(p.getParent()))
            throw new IOException("Cannot create parent: " + p);
    }
    private void requireFile(Path p) throws IOException {
        if (!fs.getFileStatus(p).isFile()) throw new IOException("Not a regular file: " + p);
    }
    private void markCreation(Path p) throws IOException {
        fs.setXAttr(p, CREATED, Instant.now().toString().getBytes(StandardCharsets.UTF_8));
    }
    private void stat(Path p) throws IOException {
        FileStatus s = fs.getFileStatus(p);
        byte[] created = fs.getXAttrs(p).get(CREATED);
        System.out.printf("%s\t%d\tmtime=%s\tcreated=%s\t%s%n", s.getPermission(), s.getLen(),
                Instant.ofEpochMilli(s.getModificationTime()),
                created == null ? "UNKNOWN(native HDFS API has no birth time)"
                    : new String(created, StandardCharsets.UTF_8) + "(application xattr)",
                s.getPath());
    }
    private void upload(java.nio.file.Path local, Path remote, String mode) throws IOException {
        if (!mode.equals("append") && !mode.equals("overwrite"))
            throw new IllegalArgumentException("Choose append or overwrite explicitly");
        if (!Files.isRegularFile(local)) throw new IOException("Missing local file: " + local);
        parents(remote);
        boolean exists = fs.exists(remote);
        if (exists) requireFile(remote);
        // Open local input before altering a destination.
        try (InputStream in = Files.newInputStream(local);
             FSDataOutputStream out = exists && mode.equals("append")
                    ? fs.append(remote) : fs.create(remote, mode.equals("overwrite"))) {
            copy(in, out);
        }
        if (!exists || mode.equals("overwrite")) markCreation(remote);
    }
    private void download(Path source, java.nio.file.Path directory) throws IOException {
        requireFile(source);
        Files.createDirectories(directory);
        String name = source.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 0; ; i++) {
            java.nio.file.Path target = directory.resolve(i == 0 ? name : stem + "_" + i + ext);
            OutputStream out;
            try {
                out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException e) { continue; }
            try (OutputStream closeOut = out; InputStream in = fs.open(source)) {
                copy(in, closeOut);
            } catch (IOException e) {
                Files.deleteIfExists(target); // Only the new, partial download is removed.
                throw e;
            }
            System.out.println(target.toAbsolutePath());
            break;
        }
    }
    private void add(Path target, java.nio.file.Path content, String position) throws IOException {
        target = fs.makeQualified(target);
        requireFile(target);
        if (position.equals("end")) {
            try (InputStream in = Files.newInputStream(content); OutputStream out = fs.append(target)) {
                copy(in, out);
            }
        } else if (position.equals("begin")) {
            // HDFS has no prepend. Stage new data and atomically rename over the original.
            FileStatus original = fs.getFileStatus(target);
            byte[] created = fs.getXAttrs(target).get(CREATED);
            Path stage = new Path(target.getParent(), "." + target.getName() + ".prepend-" + UUID.randomUUID());
            try {
                try (InputStream prefix = Files.newInputStream(content);
                     InputStream old = fs.open(target); OutputStream out = fs.create(stage, false)) {
                    copy(prefix, out);
                    copy(old, out);
                }
                fs.setPermission(stage, original.getPermission());
                if (created != null) fs.setXAttr(stage, CREATED, created);
                FileContext.getFileContext(fs.getUri(), getConf()).rename(stage, target, Options.Rename.OVERWRITE);
            } finally {
                if (fs.exists(stage)) fs.delete(stage, false);
            }
        } else throw new IllegalArgumentException("Choose begin or end");
    }
    private static void argc(String[] a, int n) {
        if (a.length != n) throw new IllegalArgumentException("Wrong argument count; run help");
    }
    public int run(String[] a) throws Exception {
        if (a.length == 0 || a[0].equals("help")) {
            System.out.println("upload LOCAL HDFS append|overwrite\n"
                    + "download HDFS LOCAL_DIR\ncat HDFS\nstat HDFS\nlist HDFS_DIR\n"
                    + "create HDFS\ndelete HDFS\nmkdir HDFS_DIR\nrmdir HDFS_DIR\n"
                    + "add HDFS LOCAL_CONTENT begin|end\nmove SOURCE DESTINATION");
            return 0;
        }
        try (FileSystem client = FileSystem.newInstance(getConf())) {
            fs = client;
            switch (a[0]) {
                case "upload": argc(a, 4); upload(java.nio.file.Paths.get(a[1]), new Path(a[2]), a[3]); break;
                case "download": argc(a, 3); download(new Path(a[1]), java.nio.file.Paths.get(a[2])); break;
                case "cat":
                    argc(a, 2); requireFile(new Path(a[1]));
                    try (InputStream in = fs.open(new Path(a[1]))) { copy(in, System.out); } break;
                case "stat": argc(a, 2); stat(new Path(a[1])); break;
                case "list":
                    argc(a, 2);
                    RemoteIterator<LocatedFileStatus> files = fs.listFiles(new Path(a[1]), true);
                    while (files.hasNext()) stat(files.next().getPath()); break;
                case "create":
                    argc(a, 2); Path p = new Path(a[1]); parents(p);
                    try (OutputStream out = fs.create(p, false)) { }
                    markCreation(p); break;
                case "delete":
                    argc(a, 2); Path d = new Path(a[1]); requireFile(d);
                    if (!fs.delete(d, false)) throw new IOException("Deletion failed: " + d); break;
                case "mkdir":
                    argc(a, 2);
                    if (!fs.mkdirs(new Path(a[1]))) throw new IOException("mkdir failed"); break;
                case "rmdir":
                    argc(a, 2); Path dir = new Path(a[1]);
                    if (!fs.getFileStatus(dir).isDirectory()) throw new IOException("Not a directory");
                    if (fs.listStatus(dir).length != 0) throw new IOException("Directory is not empty");
                    if (!fs.delete(dir, false)) throw new IOException("rmdir failed"); break;
                case "add": argc(a, 4); add(new Path(a[1]), java.nio.file.Paths.get(a[2]), a[3]); break;
                case "move":
                    argc(a, 3); Path source = new Path(a[1]); Path dest = new Path(a[2]);
                    requireFile(source);
                    if (fs.exists(dest)) throw new IOException("Destination exists: " + dest);
                    parents(dest);
                    if (!fs.rename(source, dest)) throw new IOException("Move failed"); break;
                default: throw new IllegalArgumentException("Unknown command " + a[0]);
            }
        }
        return 0;
    }
    public static void main(String[] args) {
        try { System.exit(ToolRunner.run(new HdfsCli(), args)); }
        catch (Exception e) { System.err.println("ERROR: " + e.getMessage()); System.exit(1); }
    }
}
