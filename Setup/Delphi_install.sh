#!/usr/bin/env bash

####SETTINGS####
DELPHI_NETWORK_NAME=delphi 

####DELPHI INSTALLATION#####
#########################

LOGFILE=$HOME/`date "+%F_%T"`\_$SUDO_USER.log
sudo touch $LOGFILE
sudo chown $SUDO_USER $LOGFILE

echo " Starting installation of Delphi Application"
echo " Logs can be found at: $LOGFILE"

echo " Setting up Traefik..."
sudo -u $SUDO_USER bash -c "docker network create $DELPHI_NETWORK_NAME &>> $LOGFILE"
sudo -u $SUDO_USER bash -c "docker-compose up -d &>> $LOGFILE"
echo " **Setup of Traefik completed**"

echo " Cloning Delphi ..."
sudo -u $SUDO_USER bash -c "git clone --progress https://github.com/delphi-hub/delphi.git --recurse-submodules>> $LOGFILE"
echo " **Cloning of Delphi Repositories completed**"


#######Docker Setup######
#########################
# Testing purposes:
# sudo apt-get purge docker-ce
# sudo rm -rf /var/lib/docker
# sudo rm -rf /etc/docker
# sudo groupdel docker


## Installing Images and Creating Volumes
    
echo " Building Delphi Images. This step might take several minutes"
##sudo -u $SUDO_USER bash -c "cd ..;sbt docker:publishLocal &>> $LOGFILE"
echo " Delphi-Registry Image built" 
##sudo -u $SUDO_USER bash -c "(cd ./delphi/delphi-webapi;sbt docker:publishLocal) &>> $LOGFILE"
echo " Delphi-WebApi Image built" 
##sudo -u $SUDO_USER bash -c "(cd ./delphi/delphi-webapp;sbt docker:publishLocal) &>> $LOGFILE"
echo " Delphi-WebApp Image built" 
##sudo -u $SUDO_USER bash -c "(cd ./delphi/delphi-crawler;sbt docker:publishLocal) &>> $LOGFILE"
echo " Delphi-Crawler Image built" 
##sudo -u $SUDO_USER bash -c "(cd ./delphi/delphi-management;sbt docker:publishLocal) &>> $LOGFILE"
echo " Delphi-Management Image built"
bash -c "docker images >> $LOGFILE"


echo " **Docker Images for all the components built successfully**"


#########Finished########
#########################

echo " For more information regarding the application, please have a look at the Readme file found at: https://github.com/delphi-hub/delphi-registry/blob/master/README.md"



