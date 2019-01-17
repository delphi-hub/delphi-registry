#!/usr/bin/env bash

####DELPHI INSTALLATION#####
#########################

LOGFILE=$HOME/`date "+%F_%T"`\_$SUDO_USER.log
sudo touch $LOGFILE
sudo chown $SUDO_USER $LOGFILE

printf "Starting installation of DELPHI.\n"
printf "Logs can be found at: $LOGFILE"


######Git Repository######
##########################
# Testing purposes: sudo apt remove git

# printf "Cloning Delphi..."
# bash -c "apt-get install git -y &>> $LOGFILE"
sudo -u $SUDO_USER bash -c "git clone --progress https://github.com/delphi-hub/delphi.git --recurse-submodules>> $LOGFILE"
printf " Done building Delphi.\n"


#######Docker Setup######
#########################
# Testing purposes:
# sudo apt-get purge docker-ce
# sudo rm -rf /var/lib/docker
# sudo rm -rf /etc/docker
# sudo groupdel docker

# Docker Compose
# Testing purposes:
# sudo apt-get purge docker-compose

## Installing Images and Creating Volumes
    
printf "Installing Docker images and creating volumes. This step might take several minutes. Installation details can be found in the logs...\n"

printf " Building Delphi Images\n" 
sudo -u $SUDO_USER bash -c "(cd ./delphi/delphi-webapi;sbt docker:publishLocal) &>> $LOGFILE"
printf " Built Delphi-WebApi Image\n" 
sudo -u $SUDO_USER bash -c "(cd ./delphi/delphi-webapp;sbt docker:publishLocal) &>> $LOGFILE"
printf " Built Delphi-WebApp Image\n" 
sudo -u $SUDO_USER bash -c "(cd ./delphi/delphi-crawler;sbt docker:publishLocal) &>> $LOGFILE"
printf " Built Delphi-Crawler Image\n" 
#sudo -u $SUDO_USER bash -c "(cd ./delphi-registry;sbt docker:publishLocal) &>> $LOGFILE"
#printf " Built Delphi-Registry Image\n" 
sudo -u $SUDO_USER bash -c "(cd ./delphi/delphi-management;sbt docker:publishLocal) &>> $LOGFILE"
printf " Built Delphi-Management Image\n"
bash -c "docker images >> $LOGFILE"

# bash -c "docker-compose up -d --no-recreate &>> $LOGFILE"
# bash -c "docker volume create --name IVY_REPO -d local >/dev/null &>> $LOGFILE"
# bash -c "docker volume create --name M2_REPO -d local &>> $LOGFILE"
printf " Done."


#########Finished########
#########################
printf "DELPHI Networks installed. To complete your setup, follow the remaining instructions at: https://github.com/delphi-hub\n"


