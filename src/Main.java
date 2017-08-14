import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Scanner;

/**
 * Created by andrew on 7/23/17.
 */
public class Main {

	public static void main(String[] args) throws Exception{
		HashSet<String> params = new HashSet<>();
		for(String str : args)
		{
			params.add(str);
		}

		Jobrunner jobrunner = new Jobrunner();
		jobrunner.authorize();

		if(args.length>0)
		{
			if(args[0].equals("-d")||args[0].equals("--download"))
			{
				if(args.length<2) {
					System.out.print("No target file id found.\n");
					return;
				}

				jobrunner.downloadFile(args[1]);
				return;
			}

			if(args[0].equals("-dd")||args[0].equals("--download_all"))
			{
				if(args.length<3) {
					System.out.print("Not enough args.\n");
					return;
				}

				Jobrunner.failed_list.clear();
				jobrunner.downloadFile_all(args[1],args[2]);
				if(!Jobrunner.failed_list.isEmpty())
				{
					System.out.println("Following files are not downloaded due to error occured in the downloading process:");
					for(String tmp : Jobrunner.failed_list)
					{
						System.out.println(tmp);
					}
				}
				return;
			}
		}

		if(params.contains("--update-cache")||params.contains("-u")||!Jobrunner.node_storage_file.exists())
		{
			System.out.print("Updating local file structure cache, please wait...\n");
			if(params.contains("--verbose"))
				jobrunner.generateFileCache(true);
			else
				jobrunner.generateFileCache(false);
		}

		if(params.contains("--cache-only")||params.contains("-c"))
			return;

        System.out.printf("Cached file structure found. Loading...\n");
        FileInputStream node_readin_stream = new FileInputStream(Jobrunner.node_storage_file);
        ObjectInputStream node_in_obj = new ObjectInputStream(node_readin_stream);

		Jobrunner.all_folder_nodes.clear();
		Jobrunner.all_file_nodes.clear();
		Jobrunner.all_folder_nodes= (HashMap<String, FolderObject>) node_in_obj.readObject();
		Jobrunner.all_file_nodes= (HashMap<String, FileObject>) node_in_obj.readObject();
        node_in_obj.close();
        node_readin_stream.close();

		System.out.printf("Cached file structure loaded.\n");
        System.out.printf("\n");

        String current_path="/";

        System.out.print("Awaiting input:\n");

		Scanner clin = new Scanner(System.in);
		while(true){
			String cmd = clin.nextLine();

			if(cmd.startsWith("ls")){
				jobrunner.showContent(current_path,cmd);
			}
			else if(cmd.startsWith("cd ")){
				current_path = jobrunner.switchDirectory(current_path,cmd);
			}
			else if(cmd.equals("pwd"))
			{
				System.out.println(current_path);
			}
			else if(cmd.startsWith("dtol "))
			{
				jobrunner.addToDownloadQueue(current_path,cmd);
			}
			else if(cmd.startsWith("dtall "))
			{
				jobrunner.downloadAllByExt(cmd);
			}
			else if(cmd.equals("edq"))
			{
				jobrunner.exportDownloadQueue();
			}
			else
			{
				System.out.printf("Invalid Command.\n");
				continue;
			}
		}
	}
}




























