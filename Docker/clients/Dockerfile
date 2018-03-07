FROM library/centos:7
MAINTAINER Jiri Danek <jdanek@redhat.com>

RUN yum install -y centos-release-scl
RUN yum install -y \
    cyrus-sasl* \
    gcc \
    python27
RUN scl enable python27 'pip install --user python-qpid-proton'

RUN yum install -y \
    git

RUN git clone https://github.com/rh-messaging/cli-proton-python.git

ADD build/libs/djtests-agent-0.1.42.QR1.jar /
