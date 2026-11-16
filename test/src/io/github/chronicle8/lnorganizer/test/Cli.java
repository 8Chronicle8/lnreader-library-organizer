package io.github.chronicle8.lnorganizer.test;

import io.github.chronicle8.lnorganizer.core.BackupReader;
import io.github.chronicle8.lnorganizer.core.Library;
import io.github.chronicle8.lnorganizer.core.ListExport;
import io.github.chronicle8.lnorganizer.core.ListParse;
import io.github.chronicle8.lnorganizer.core.Organize;
import io.github.chronicle8.lnorganizer.core.OutputCheck;
import io.github.chronicle8.lnorganizer.core.Report;
import io.github.chronicle8.lnorganizer.core.ZipIndex;
import io.github.chronicle8.lnorganizer.core.ZipRewrite;
import io.github.chronicle8.lnorganizer.io.ChannelSource;
import io.github.chronicle8.lnorganizer.io.PlanWriter;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Desktop driver for the same core the app uses:
 *   export   BACKUP.zip [plain|ai] [WANTED]   list to stdout
 *   organize BACKUP.zip LIST.txt OUT.zip      write an organized backup (OUT must not exist)
 *   dump     BACKUP.zip                       categories and novels, for cross-checking
 */
public final class Cli {
    private Cli() {}

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, "UTF-8");
        if (args.length < 2) usage();
        Path backup = Paths.get(args[1]);
        try (FileChannel ch = FileChannel.open(backup, StandardOpenOption.READ)) {
            ZipIndex zip = ZipIndex.open(new ChannelSource(ch));
            Library lib = BackupReader.read(zip, null);
            switch (args[0]) {
                case "export": {
                    ListExport.Mode mode = args.length > 2 && args[2].equals("ai") ? ListExport.Mode.AI : ListExport.Mode.PLAIN;
                    if (args.length > 2 && !args[2].equals("ai") && !args[2].equals("plain")) usage();
                    out.print(ListExport.export(lib, backup.getFileName().toString(), mode, args.length > 3 ? args[3] : ""));
                    break;
                }
                case "organize": {
                    if (args.length != 4) usage();
                    String text = new String(Files.readAllBytes(Paths.get(args[2])), StandardCharsets.UTF_8);
                    Path target = Paths.get(args[3]);
                    if (Files.exists(target)) throw new IllegalArgumentException(target + " exists; not overwriting it");
                    Organize.Result r = Organize.build(lib, ListParse.parse(text, lib));
                    out.print(Report.render(lib, r));
                    ZipRewrite.Plan plan = ZipRewrite.replaceEntry(zip, BackupReader.CATEGORY_JSON, r.categoryJson, 64 << 20);
                    Path part = target.resolveSibling(target.getFileName() + ".part");
                    try (OutputStream os = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW)) {
                        PlanWriter.write(plan, new ChannelSource(ch), os, null);
                    }
                    try (FileChannel oc = FileChannel.open(part, StandardOpenOption.READ)) {
                        OutputCheck.verify(zip, ZipIndex.open(new ChannelSource(oc)), BackupReader.CATEGORY_JSON, r.categoryJson, null);
                    } catch (Exception e) {
                        Files.delete(part);
                        throw e;
                    }
                    Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
                    out.println("wrote and verified " + target + " (" + plan.totalLength + " bytes)");
                    break;
                }
                case "dump": {
                    for (Library.Category c : lib.categories) {
                        List<Integer> ids = new ArrayList<>(c.novelIds);
                        Collections.sort(ids);
                        out.println("category\t" + c.id + "\t" + c.trimmedName + "\t" + ids);
                    }
                    for (Library.Novel n : lib.novels.values()) {
                        out.println("novel\t" + n.id + "\t" + n.pluginId + "\t" + n.inLibrary + "\t" + n.isLocal + "\t" + n.name);
                    }
                    break;
                }
                default:
                    usage();
            }
        }
    }

    private static void usage() {
        System.err.println("usage: Cli export BACKUP.zip [plain|ai] [WANTED] | organize BACKUP.zip LIST.txt OUT.zip | dump BACKUP.zip");
        System.exit(64);
    }
}
