#!/bin/bash
function usage
{
    echo "Usage: `basename $0` <filepath> <hostname> <hanshakeport> <windowsize> <timout>"
    echo ""
    echo "<filepath> - path of the file to be sent"
    echo "<hostname> - the hostname of the server waiting for files"
    echo "<hanshakeport> - the TCP port on which the server is ready to hand-shake"
    echo "<windowsize> - window size of FFTP used by the client"
    echo "<timout> - time out value of FFTP used by the client"
    echo ""
}

FILENAME=$1
HOSTNAME=$2
HANDSHAKEPORT=$3
WINDOWSIZE=$4
TIMEOUT=$5

if [ "${FILENAME}" == "" -o "${HOSTNAME}" == "" -o "${FILENAME}" == "" -o "${HANDSHAKEPORT}" == "" -o "${WINDOWSIZE}" == "" -o "${TIMEOUT}" == "" ] ; then
    echo "Error: All arguments are required."
    echo ""
    usage
    exit 1
fi

DIR=`dirname $0`
java -jar ${DIR}/../dist/fftpclient.jar ${FILENAME} ${HOSTNAME} ${HANDSHAKEPORT} ${WINDOWSIZE} ${TIMEOUT}