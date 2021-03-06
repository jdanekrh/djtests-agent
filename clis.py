from conf import new_cli

# this is actually https://docs.bazel.build/versions/master/skylark/language.html, not Python

cli_java_home = "/home/jdanek/Work/repos/cli-java"

cli_python_home = "/home/jdanek/Work/repos/dtests/dtests/node_data/clients/python"
python_interpreter = "/home/jdanek/.virtualenvs/dtests/bin/python2"

cli_ruby_home = "/home/jdanek/Work/repos/cli-proton-ruby/bin"
ruby_interpreter = 'ruby'

cli_cpp_home = "/home/jdanek/Work/repos/dtests/dtests/node_data/clients/cpp/cmake-build-debug/target/bin"

enable_tracing = []


def java_cli(file, kind):
    return new_cli(
        directory=cli_java_home,
        prefix_args=['java',
                     # '-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005',
                     # '-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005',  # debugger
                     # '-agentlib:jdwp=transport=dt_socket,server=n,address=localhost:5005,suspend=y',
                     '-jar', file, kind],
        environment={'PN_TRACE_FRM': '1'} if enable_tracing else {},
    )


def java_clis(file):
    return {k: java_cli(file, k) for k in ['sender', 'receiver', 'connector']}


def python_cli(file):
    return new_cli(
        directory=cli_python_home,
        prefix_args=[python_interpreter, file],
        environment={
            'PYTHONUNBUFFERED': '1',
            'PYTHONPATH': '.',
            # 'LANG': 'en_US.ascii',
        },
    )


def ruby_cli(file):
    return new_cli(
        directory=cli_ruby_home,
        prefix_args=[ruby_interpreter, '-e', '$stdout.sync=true;$stderr.sync=true;load($0=ARGV.shift)', file],
        environment={'PN_TRACE_FRM': '1'} if enable_tracing else {},
    )


def cpp_cli(file):
    return new_cli(
        directory=cli_cpp_home,
        prefix_args=[cli_cpp_home + '/' + file],
        environment={'PN_TRACE_FRM': '1'} if enable_tracing else {},
        # prefix_args=['rr', 'record', cli_cpp_home + '/' + file],
    )


def proton_clis(cli_func, file_tmpl):
    return {k: cli_func(file_tmpl % k) for k in ['sender', 'receiver', 'connector']}


# configure clis

# first cli, do not use definitions other than python_cli here
conf.cli('aac5', tags=['python:2'],
         sender=python_cli("aac5_sender.py"),
         receiver=python_cli("aac5_receiver.py"),
         connector=python_cli("aac5_connector.py"))

# aac1_version = 'LATEST'
# aac1_version = '0.26.0.redhat-1'
aac1_version = '0.31.0.redhat-1'
aac1_jar = "cli-qpid-jms/target/cli-qpid-jms-1.2.2-SNAPSHOT-%s.jar" % aac1_version
conf.cli('aac1', **java_clis(aac1_jar))

# aoc7_version = 'LATEST'
aoc7_version = '5.11.0.redhat-630254'
aoc7_jar = "cli-activemq/target/cli-activemq-1.2.2-SNAPSHOT-%s.jar" % aoc7_version
conf.cli('aoc7',
         **java_clis(aoc7_jar))

acce_version = 'LATEST'
# acce_version = '2.4.0.amq-710004-redhat-1'
acce_jar = "cli-artemis-jms/target/cli-artemis-jms-1.2.2-SNAPSHOT-%s.jar" % aoc7_version
conf.cli('acce',
         **java_clis(acce_jar))

conf.cli('aacf',
         **proton_clis(ruby_cli, "cli-proton-ruby-%s"))

conf.cli('aac3',
         **proton_clis(cpp_cli, "aac3_%s"))

conf.cli('aac0',
         **proton_clis(cpp_cli, "aac0_%s"))


def netcore_cli(type):
    return new_cli(
        directory='/home/jdanek/Work/repos/cli-netlite/NetCore%s/bin/Debug/netcoreapp2.0/' % type.capitalize(),
        prefix_args=['dotnet', 'cli-netlite-core-%s.dll' % type] +
                    (['--log-lib=TRANSPORT_FRM'] if enable_tracing else []),
    )


conf.cli('aac2',
         sender=netcore_cli("sender"),
         receiver=netcore_cli("receiver"),
         connector=netcore_cli("connector"))


def node_cli(type):
    return new_cli(
        directory='/home/jdanek/Work/repos/cli-rhea',
        prefix_args=['node', 'bin/%s-client.js' % type] +
                    (['--log-lib=TRANSPORT_FRM'] if enable_tracing else []),
    )


conf.cli('aacb',
         sender=node_cli("sender"),
         receiver=node_cli("receiver"),
         connector=node_cli("connector"))
