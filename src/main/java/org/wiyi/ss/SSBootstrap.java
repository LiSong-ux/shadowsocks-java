package org.wiyi.ss;

import org.apache.commons.cli.*;
import org.wiyi.ss.core.SSConfig;
import org.wiyi.ss.core.config.SSConfigFactory;
import org.wiyi.ss.core.config.SSConfigValidator;
import org.wiyi.ss.utils.SSCommandLineUtils;
import org.wiyi.ss.utils.VersionUtils;

import java.io.PrintWriter;

/**
 * 这是整个工程的启动类，运行jar包时添加的参数由该类接收
 */
public class SSBootstrap {
    public static final String SS_LOCAL = "sslocal";
    public static final String SS_SERVER = "ssserver";
    private Server server;

    public static void main(String[] args) {
        new SSBootstrap().flight(args);
    }

    public void flight(String[] bootArgs) {
        if (bootArgs.length == 0 || !isValidCommand(bootArgs[0])) {
            System.err.println("Invalid command line arguments");
            System.exit(1);
        }

        String command = bootArgs[0];
        boolean isSSLocal = command.equalsIgnoreCase(SS_LOCAL);

        CommandLineParser parser = new DefaultParser();
        // 获取应用的启动参数对象Options
        Options options = isSSLocal ? SSCommandLineUtils.ssLocalOptions() : SSCommandLineUtils.ssServerOptions();

        try {
            CommandLine cmd = parser.parse(options, bootArgs);
            if (cmd.hasOption("h") || cmd.getArgList().isEmpty()) {
                // 如果启动命令中有“h”选项或者参数为空，则打印所有的命令选项并退出
                printHelper(options, command);
                System.exit(0);
            } else if (cmd.hasOption("v")) {
                // 如果启动命令中有“v”选项，则打印应用版本并退出
                System.out.println(VersionUtils.getVersion());
                System.exit(0);
            }

            SSConfig config = SSConfigFactory.loadFromCommandLine(cmd);
            SSConfigValidator.validate(config, isSSLocal);
            server = isSSLocal ? new SSLocal(config) : new SSServer(config);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            printHelper(options, command);
            System.exit(1);
        }

        server.open();
    }

    public void land() {
        if (server != null && !server.isClosed()) {
            server.close();
        }

        System.exit(0);
    }

    /**
     * 判断是不是合法的命令行，只有 sslocal或ssserver开头的命令才是合法的
     *
     * @param argv 用户输入的命令行
     * @return 是否合法
     */
    private boolean isValidCommand(String argv) {
        return SS_LOCAL.equalsIgnoreCase(argv) || SS_SERVER.equalsIgnoreCase(argv);
    }

    // 打印所有的命令行选项
    private void printHelper(Options all, String command) {
        Options options = new Options();
        Options flags = new Options();
        for (Option option : all.getOptions()) {
            if (option.hasArg()) {
                options.addOption(option);
            } else {
                flags.addOption(option);
            }
        }

        HelpFormatter formatter = new HelpFormatter();
        PrintWriter writer = new PrintWriter(System.out);

        formatter.printUsage(writer, formatter.getWidth(), command + " [FLAGS] [OPTIONS]");
        int with = formatter.getWidth() + 35;

        writer.println("\nflags:");
        formatter.printOptions(writer, with, flags, formatter.getLeftPadding(), formatter.getDescPadding());

        writer.println("\noptions:");
        formatter.printOptions(writer, with, options, formatter.getLeftPadding(), formatter.getDescPadding());
        writer.flush();
    }
}
