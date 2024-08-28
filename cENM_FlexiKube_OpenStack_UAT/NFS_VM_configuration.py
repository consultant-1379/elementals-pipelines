from paramiko import *
import sys
import socket
import os
import argparse
import time
import json
import csv


def create_ssh_connection_with_NFS(nfs_ip):
    ssh = SSHClient()
    ssh.set_missing_host_key_policy(AutoAddPolicy())
    print("\nConnecting to NFS: " + nfs_ip + "\n")

    try:
        ssh_response = []
        ssh.connect(nfs_ip, username="centos", password="centos")
        print("Connected to NFS \n")
        return ssh
    except SSHException:
        print("Unable to ssh into NFS: ")
        sys.exit()
    except socket.error:
        print(
            "Socket Error: Connection attempt failed because the connected party did not properly respond after a period of time, or established connection failed because connected host: has failed to respond")
        sys.exit()

def configure_NFS_VM(ssh):
    def check_cpu():
        print("\nCHECKING CPU...\n")
        ssh_stdin, ssh_stdout, ssh_stderr = ssh.exec_command("lscpu")
        output = ssh_stdout.read()
        with open('nfs_configuration.txt', 'a') as reportfile:
            reportfile.write("\nNFS CPU:\n----\n{noformat}\n")
            reportfile.write(output)
            reportfile.write("\n{noformat}\n")
        print(output)

    def check_ram():
        print("\nCHECKING RAM...\n")
        ssh_stdin, ssh_stdout, ssh_stderr = ssh.exec_command("free -g")
        output = ssh_stdout.read()
        with open('nfs_configuration.txt', 'a') as reportfile:
            reportfile.write("\nNFS RAM:\n----\n{noformat}\n")
            reportfile.write(output)
            reportfile.write("\n{noformat}\n")
        print(output)

    def check_storage():
        print("\nCHECKING STORAGE...\n")
        ssh_stdin, ssh_stdout, ssh_stderr = ssh.exec_command("df -h")
        output = ssh_stdout.read()
        with open('nfs_configuration.txt', 'a') as reportfile:
            reportfile.write("\n\nNFS Storage:\n----\n{noformat}\n")
            reportfile.write(output)
            reportfile.write("\n{noformat}\n")
        print(output)

    def create_NFS_share_folder():
        print("\nCreating the directory: /share/ericsson/%s\n" % nfs_path_folder)

        # Create the share NFS directory (and any sub-directories)
        ssh_stdin, ssh_stdout, ssh_stderr = ssh.exec_command("sudo mkdir -p /share/ericsson/%s" % nfs_path_folder)
        print(ssh_stdout.read())
        print(ssh_stderr.read())

        # Show if the folder has been created
        ssh_stdin, ssh_stdout, ssh_stderr = ssh.exec_command("ls -rtl /share/ericsson/")
        with open('nfs_configuration.txt', 'a') as reportfile:
            reportfile.write("\nShare Folder Creation:\n----\n{noformat}\n")
            reportfile.write("ls -rtl /share/ericsson/\n")
            reportfile.write(ssh_stdout.read())

        # make the share NFS directory available for the NFS on the k8s cluster
        ssh_stdin, ssh_stdout, ssh_stderr = ssh.exec_command("sudo exportfs -a")
        print(ssh_stdout.read())
        print(ssh_stderr.read())

    def perform_exports_file_configuration():
        print("\n/etc/exports file configuration...")
        print("Current /etc/exports file contents:")
        ssh_stdin, ssh_stdout, ssh_stderr = ssh.exec_command("sudo cat /etc/exports")
        print(ssh_stdout.read())
        print(ssh_stderr.read())

        # APPLY THE PARAMETERS
        print("\nUpdating /etc/exports file contents...")
        exports_file_contents = "/share/ericsson/%s *(rw,async,no_root_squash,no_all_squash)" % (nfs_path_folder)
        ssh_stdin, ssh_stdout, ssh_stderr = ssh.exec_command(
            "echo '%s' | sudo tee /etc/exports" % (exports_file_contents))
        print(ssh_stdout.read())
        print(ssh_stderr.read())

        ssh_stdin, ssh_stdout, ssh_stderr = ssh.exec_command("sudo cat /etc/exports")
        with open('nfs_configuration.txt', 'a') as reportfile:
            reportfile.write("\n/etc/exports file has been updated with the below parameter/s:\n")
            reportfile.write(ssh_stdout.read())

        # EXPOSE THE SHARE DIRECTORY FOR NFS CLIENTS
        print("\nExposing directory for NFS clients to mount")
        ssh_stdin, ssh_stdout, ssh_stderr = ssh.exec_command("sudo exportfs -a")

    def perform_ntp_configuration():
        print("\n/etc/chrony.conf file configuration...\n")
        ssh_stdin, ssh_stdout, ssh_stderr = ssh.exec_command("sudo cat /etc/chrony.conf")

        # Output contents of chrony.conf file before update to file "old_chrony.conf"
        with open('old_chrony.conf', 'a') as reportfile:
            reportfile.write(ssh_stdout.read())

        print("\nUpdating /etc/chrony.conf file contents...\n")
        # OPEN FILE CONTAINING THE CHRONY PARAMETERS AND ADD TO VARIABLE called chrony_config_file_contents
        global chrony_config_file_contents
        with open("cENM_FlexiKube_OpenStack_UAT/chrony.conf_parameters", "r") as file:
        #with open("chrony.conf_parameters", "r") as file:
            chrony_config_file_contents = file.read().rstrip()

        # APPLY THE PARAMETERS
        ssh_stdin, ssh_stdout, ssh_stderr = ssh.exec_command(
            "echo '%s' | sudo tee /etc/chrony.conf" % (chrony_config_file_contents))
        print(ssh_stdout.read())
        print(ssh_stderr.read())

        ssh_stdin, ssh_stdout, ssh_stderr = ssh.exec_command("sudo cat /etc/chrony.conf")
        with open('nfs_configuration.txt', 'a') as reportfile:
            reportfile.write("\nchrony.conf file has been updated with the below parameters:\n")
            reportfile.write(ssh_stdout.read())

        # RESTART THE CHRONYD SERVICE AND CHECK STATUS
        print("\nStopping the chronyd.service ...")
        ssh_stdin, ssh_stdout, ssh_stderr = ssh.exec_command("sudo systemctl stop chronyd.service")
        print(ssh_stdout.read())
        print(ssh_stderr.read())

        print("\nwaiting 20 seconds before continuing...\n")
        time.sleep(20)

        print("\nStatus of chronyd.service ...")
        ssh_stdin, ssh_stdout, ssh_stderr = ssh.exec_command("sudo systemctl status chronyd.service")
        print(ssh_stdout.read())
        print(ssh_stderr.read())

        print("\nStarting the chronyd.service ...")
        ssh_stdin, ssh_stdout, ssh_stderr = ssh.exec_command("sudo systemctl start chronyd.service")
        print(ssh_stdout.read())
        print(ssh_stderr.read())

        print("\nwaiting 20 seconds before continuing...\n")
        time.sleep(20)

        print("\nStatus of chronyd.service ...")
        ssh_stdin, ssh_stdout, ssh_stderr = ssh.exec_command("sudo systemctl status chronyd.service")
        print(ssh_stdout.read())
        print(ssh_stderr.read())

    # run each of the nfs configuration methods
    print("\n\n----------------------------------------------------")
    print("NFS CONFIGURATION START")
    print("----------------------------------------------------\n")

    check_cpu()
    check_ram()
    check_storage()

    create_NFS_share_folder()
    perform_exports_file_configuration()
    perform_ntp_configuration()

    print("\n----------------------------------------------------")
    print("NFS CONFIGURATION END")
    print("----------------------------------------------------\n\n")

# endregion

def main():
    global nfs_ip, nfs_storage_class, nfs_path_folder, namespace

    #assigning the build parameters passed from jenkins file onto respective variable
    nfs_ip = sys.argv[2]
    nfs_storage_class = sys.argv[1]
    nfs_path_folder = sys.argv[3]

    print(nfs_ip, nfs_storage_class, nfs_path_folder)

    # CREATE SSH CONNECTION WITH THE NFS VM
    try:
        ssh = create_ssh_connection_with_NFS(nfs_ip)
        # CONFIGURE THE NFS VM
        configure_NFS_VM(ssh)
        print("Closing SSH connection with the NFS VM")
        ssh.close()
    except SSHException:
        print("Unable to ssh into NFS: ")
    except socket.error:
        print(
            "Socket Error: Connection attempt failed because the connected party did not properly respond after a period of time, or established connection failed because connected host: has failed to respond")

if __name__ == '__main__':
    main()
