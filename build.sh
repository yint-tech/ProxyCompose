#!/usr/bin/env bash


now_dir=`pwd`
cd `dirname $0`

shell_dir=`pwd`

mvn  -Dmaven.test.skip=true package appassembler:assemble

if [[ $? != 0 ]] ;then
    echo "build sekiro jar failed"
    exit 2
fi

chmod +x target/proxy-compose/bin/ProxyComposed.sh
proxy_compose_dir=target/proxy-compose

cd ${proxy_compose_dir}

zip -r proxy-compose.zip ./*

mv proxy-compose.zip ../

cd ${now_dir}

echo "the output zip file:  target/proxy-compose.zip"