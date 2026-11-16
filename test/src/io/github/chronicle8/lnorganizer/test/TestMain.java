package io.github.chronicle8.lnorganizer.test;

/** Runs every suite and gives one verdict; exit status 1 on any failure, 2 on a skip when --no-skip is given. */
public final class TestMain {
    private TestMain() {}

    public static void main(String[] args) {
        boolean noSkip = args.length > 0 && args[0].equals("--no-skip");
        JsonTests.run();
        ZipTests.run();
        ListTests.run();
        LayeringTests.run();
        RealBackupTests.run();
        System.out.println();
        System.out.println("passed " + Check.passed + ", failed " + Check.failed.size() + ", skipped " + Check.skipped.size());
        for (String f : Check.failed) System.out.println("  failed:  " + f);
        for (String s : Check.skipped) System.out.println("  skipped: " + s);
        if (!Check.failed.isEmpty()) System.exit(1);
        if (noSkip && !Check.skipped.isEmpty()) System.exit(2);
    }
}
