################################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
################################################################################

# Create host mount directory and copy
mkdir -p /tmp/hadoop_name
mkdir -p /tmp/hadoop_data

WS_ROOT=`dirname $PWD`
# restart cluster
HUDI_WS=${WS_ROOT} docker-compose -f compose/docker-compose_hadoop284_hive233_spark231.yml down
HUDI_WS=${WS_ROOT} docker-compose -f compose/docker-compose_hadoop284_hive233_spark231.yml pull
rm -rf /tmp/hadoop_data/*
rm -rf /tmp/hadoop_name/*
sleep 5
HUDI_WS=${WS_ROOT} docker-compose -f compose/docker-compose_hadoop284_hive233_spark231.yml up -d
sleep 15

docker exec -it adhoc-1 /bin/bash /var/hoodie/ws/docker/demo/setup_demo_container.sh
docker exec -it adhoc-2 /bin/bash /var/hoodie/ws/docker/demo/setup_demo_container.sh
