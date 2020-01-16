/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.jakartaee;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class Migration {

    private File source;
    private File destination;
    private final List<Converter> converters;

    public Migration() {
        // Initialise the converters
        converters = new ArrayList<>();

        converters.add(new TextConverter());
        converters.add(new ClassConverter());

        // Final converter is the NoOpConverter
        converters.add(new NoOpConverter());
    }


    public void setSource(File source) {
        if (!source.canRead()) {
            // TODO i18n
            throw new IllegalArgumentException();
        }
        this.source = source;
    }


    public void setDestination(File destination) {
        // TODO validate
        this.destination = destination;
    }


    public boolean execute() throws IOException {
        // TODO validate arguments

        if (source.isDirectory()) {
            migrateDirectory(source, destination);
        } else {
            // Single file
            migrateFile(source, destination);
        }
        return false;
    }


    private void migrateDirectory(File src, File dest) throws IOException {
        String[] files = src.list();
        for (String file : files) {
            File srcFile = new File(src, file);
            File destFile = new File(dest, file);
            if (srcFile.isDirectory()) {
                destFile.mkdirs();
                migrateDirectory(srcFile, destFile);
            } else {
                migrateFile(srcFile, destFile);
            }
        }
    }


    private void migrateFile(File src, File dest) throws IOException {
        try (InputStream is = new FileInputStream(src);
                OutputStream os = new FileOutputStream(dest)) {
            migrateStream(src.getName(), is, os);
        }
    }


    private void migrateArchive(InputStream src, OutputStream dest) throws IOException {
        try (JarInputStream jarIs = new JarInputStream(new NonClosingInputStream(src));
                JarOutputStream jarOs = new JarOutputStream(new NonClosingOutputStream(dest))) {
            Manifest manifest = jarIs.getManifest();
            if (manifest != null) {
                updateVersion(manifest);
                JarEntry manifestEntry = new JarEntry(JarFile.MANIFEST_NAME);
                jarOs.putNextEntry(manifestEntry);
                manifest.write(jarOs);
            }
            JarEntry jarEntry;
            while ((jarEntry = jarIs.getNextJarEntry()) != null) {
                String sourceName = jarEntry.getName();
                System.out.println("Migrating JarEntry [" + sourceName + "]");
                String destName = Util.convert(sourceName);
                JarEntry destEntry = new JarEntry(destName);
                jarOs.putNextEntry(destEntry);
                migrateStream(destEntry.getName(), jarIs, jarOs);
            }
        }
    }


    private void migrateStream(String name, InputStream src, OutputStream dest) throws IOException {
        System.out.println("Migrating stream [" + name + "]");
        if (isArchive(name)) {
            migrateArchive(src, dest);
        } else {
            for (Converter converter : converters) {
                if (converter.accpets(name)) {
                    converter.convert(src, dest);
                    break;
                }
            }
        }
    }


    private void updateVersion(Manifest manifest) {
        updateVersion(manifest.getMainAttributes());
        for (Attributes attributes : manifest.getEntries().values()) {
            updateVersion(attributes);
        }
    }


    private void updateVersion(Attributes attributes) {
        if (attributes.containsKey(Attributes.Name.IMPLEMENTATION_VERSION)) {
            String newValue = attributes.get(Attributes.Name.IMPLEMENTATION_VERSION) + "-" + Info.getVersion();
            attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, newValue);
        }
    }


    public static void main(String[] args) {
        if (args.length != 2) {
            usage();
            System.exit(1);
        }
        Migration migration = new Migration();
        migration.setSource(new File(args[0]));
        migration.setDestination(new File(args[1]));
        boolean result = false;
        try {
            result = migration.execute();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        // Signal caller that migration failed
        if (!result) {
            System.exit(1);
        }
    }


    private static void usage() {
        System.out.println("Usage: Migration <source> <destination>");
    }


    private static boolean isArchive(String fileName) {
        return fileName.endsWith(".jar") || fileName.endsWith(".war") || fileName.endsWith(".zip");
    }
}
