import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class VerifyBytecode {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: java VerifyBytecode.java <jar-or-directory>...");
            System.exit(2);
        }
        List<File> jars = new ArrayList<>();
        for (String arg : args) {
            collectJars(new File(arg), jars);
        }
        if (jars.isEmpty()) {
            System.err.println("no jars found");
            System.exit(2);
        }
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < jars.size(); i++) {
            urls[i] = jars.get(i).toURI().toURL();
        }
        TreeMap<String, String> failures = new TreeMap<>();
        TreeMap<String, String> unresolved = new TreeMap<>();
        int checked = 0;
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            for (File jar : jars) {
                for (String name : classNames(jar)) {
                    checked++;
                    try {
                        Class<?> type = Class.forName(name, false, loader);
                        type.getDeclaredMethods();
                    } catch (VerifyError | ClassFormatError | IncompatibleClassChangeError error) {
                        failures.putIfAbsent(name, jar.getName() + " | " + firstLine(error));
                    } catch (LinkageError | SecurityException error) {
                        unresolved.putIfAbsent(name, jar.getName() + " | " + firstLine(error));
                    }
                }
            }
        }
        System.out.println("jars=" + jars.size() + " classes=" + checked + " failures=" + failures.size() + " unresolved=" + unresolved.size());
        if (Boolean.getBoolean("verify.showUnresolved")) {
            unresolved.forEach((name, detail) -> System.out.println("UNRESOLVED " + name + " | " + detail));
        }
        failures.forEach((name, detail) -> System.out.println("FAIL " + name + " | " + detail));
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    private static void collectJars(File file, List<File> jars) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return;
            java.util.Arrays.sort(children);
            for (File child : children) {
                if (child.isFile() && child.getName().endsWith(".jar")) jars.add(child);
            }
        } else if (file.isFile() && file.getName().endsWith(".jar")) {
            jars.add(file);
        }
    }

    private static List<String> classNames(File jar) throws IOException {
        List<String> names = new ArrayList<>();
        try (JarFile file = new JarFile(jar)) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                String entry = entries.nextElement().getName();
                if (!entry.endsWith(".class") || entry.startsWith("META-INF/")) continue;
                if (entry.endsWith("module-info.class") || entry.endsWith("package-info.class")) continue;
                names.add(entry.substring(0, entry.length() - 6).replace('/', '.'));
            }
        }
        return names;
    }

    private static String firstLine(Throwable error) {
        String message = String.valueOf(error.getMessage());
        int newline = message.indexOf('\n');
        return error.getClass().getSimpleName() + ": " + (newline < 0 ? message : message.substring(0, newline));
    }
}
