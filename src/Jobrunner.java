import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.*;

import java.io.*;
import java.io.File;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import static java.lang.System.exit;

/**
 * Created by andrew on 7/23/17.
 */
public class Jobrunner {
    /** Global instance of the JSON factory. */
    static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

    /** Global instance of the HTTP transport. */
    static HttpTransport HTTP_TRANSPORT;

    /** Global instance of the {@link FileDataStoreFactory}. */
    static FileDataStoreFactory DATA_STORE_FACTORY;

    static final java.io.File DATA_STORE_DIR = new File(System.getProperty("user.home")+"/G-Mapper");
	static final java.io.File CRED_JSON_LOCATION = new File(System.getProperty("user.home")+"/G-Mapper/tester1.json");

    static {
        try {
            HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
            DATA_STORE_FACTORY = new FileDataStoreFactory(DATA_STORE_DIR);
        } catch (Throwable t) {
            t.printStackTrace();
            exit(1);
        }
    }

    Credential credential;
    Drive driveService;

	static File node_storage_file = new File(System.getProperty("user.home")+"/G-Mapper/all_nodes.xr");
	static HashMap<String,FolderObject> all_folder_nodes = new HashMap<>();
	static HashMap<String,FileObject> all_file_nodes = new HashMap<>();

	//static HashMap<String,FolderObject> all_folder_nodes_fid = new HashMap<>();
	//static HashMap<String,FileObject> all_file_nodes_fid = new HashMap<>();

	static HashSet<FileObject> download_queue = new HashSet<>();
	static double total_download_size=0;
	static int total_download_items=0;

	static ArrayList<String> failed_list = new ArrayList<>();

    /** Global instance of the scopes required by this quickstart.*/
    private static final List<String> SCOPES = Arrays.asList(DriveScopes.DRIVE_READONLY);


    public void authorize() throws IOException {
        double totalSize=0;

        if(!DATA_STORE_DIR.exists())
        	DATA_STORE_DIR.mkdirs();

	    if(!CRED_JSON_LOCATION.canRead())
	    {
	    	System.out.println("Error reading credential json file. Exiting...");
		    System.exit(1);
	    }

        // Load client secrets.
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new FileReader(CRED_JSON_LOCATION));

        // Build flow and trigger user authorization request.
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                        HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                        .setDataStoreFactory(DATA_STORE_FACTORY)
                        .setAccessType("offline")
                        .build();

        Credential credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
        System.out.println("Credentials saved to " + DATA_STORE_DIR.getAbsolutePath());

        this.credential=credential;
        this.driveService = new Drive.Builder(
                Jobrunner.HTTP_TRANSPORT, Jobrunner.JSON_FACTORY, this.credential)
                .setApplicationName("G-Mapper")
                .build();
    }

    public FolderObject generateRootDirectoryNode() throws Exception
    {
        FileList result = this.driveService.files().list()
                .setQ("'root' in parents")
                .setPageSize(20)
                .setFields("nextPageToken, files(id, name, parents, mimeType)")
                .execute();

        String pageToken = result.getNextPageToken();
        List<com.google.api.services.drive.model.File> files_list = result.getFiles();

        while(pageToken!=null){
            result = this.driveService.files().list()
                    .setQ("'root' in parents")
                    .setPageSize(20)
                    .setFields("nextPageToken, files(id, name, parents, mimeType)")
                    .setPageToken(pageToken)
                    .execute();
            pageToken = result.getNextPageToken();
            files_list.addAll(result.getFiles());
        }

        /*if (files_list == null || files_list.size() == 0) {
            System.out.println("No files found.");
        } else {
            System.out.println("Files:");
            for (com.google.api.services.drive.model.File file : files_list) {
                System.out.printf("%s (%s) %s %s\n", file.getName(), file.getId(), file.getMimeType(), file.getParents());
            }
        }*/

        String current_path="/";
        int current_level=0;
        FolderObject root_directory = new FolderObject("root","root",0,"/");

        HashSet<GTuple> tmp_folders = new HashSet<>();
        HashSet<GTuple> tmp_files = new HashSet<>();
        tmp_folders.clear();
        tmp_files.clear();

        for (com.google.api.services.drive.model.File file : files_list) {
            if(file.getMimeType().equals("application/vnd.google-apps.folder")) {
                tmp_folders.add(new GTuple(file.getId(),file.getName()));
            }
            else {
                tmp_files.add(new GTuple(file.getId(),file.getName()));
            }
        }

        root_directory.contentFolders.addAll(tmp_folders);
        root_directory.contentFiles.addAll(tmp_files);
        tmp_folders.clear();
        tmp_files.clear();

        return root_directory;
    }

    public HashMap<String,FolderObject> buildNextLevel_Folder(HashMap<String,FolderObject> last_level) throws Exception
    {
        HashMap<String,FolderObject> next_level = new HashMap<String,FolderObject>();
        int counter=0;

        for (String key : last_level.keySet())
        {
            String query = "'"+last_level.get(key).folder_id+"' in parents";
            List<com.google.api.services.drive.model.File> files_list = this.executeQuery(query);

            for (com.google.api.services.drive.model.File file : files_list)
            {
                if(file.getMimeType().equals("application/vnd.google-apps.folder"))
                {
                    counter++;

                    String current_path=last_level.get(key).full_path+file.getName()+"/";
                    int current_level=last_level.get(key).folder_level+1;

                    FolderObject creating = new FolderObject(file.getName(),file.getId(),current_level,current_path);

                    this.fillContents(creating);

                    next_level.put(creating.full_path,creating);
                }
            }
        }
        if(counter>0)
            return next_level;
        else
            return null;
    }

    public HashMap<String, FileObject> buildNextLevel_File(HashMap<String,FolderObject> last_level) throws Exception
    {
        HashMap<String,FileObject> next_level = new HashMap<String,FileObject>();
        int counter=0;

        for (String key : last_level.keySet())
        {
            String query = "'"+last_level.get(key).folder_id+"' in parents";
            List<com.google.api.services.drive.model.File> files_list = this.executeQuery(query);

            for (com.google.api.services.drive.model.File file : files_list)
            {
                if(!(file.getMimeType().equals("application/vnd.google-apps.folder")))
                {
                    counter++;
                    String current_path=last_level.get(key).full_path+file.getName();
                    int current_level=last_level.get(key).folder_level+1;

                    FileObject creating = new FileObject(file.getName(),file.getId(),file.getMimeType(),current_level,current_path);
                    if(file.getSize()!=null)
                        creating.size=file.getSize();
                    else
                        creating.size=-1;

                    next_level.put(creating.full_path,creating);
                }
            }
        }
        if(counter>0)
            return next_level;
        else
            return null;
    }

    private List<com.google.api.services.drive.model.File> executeQuery(String query) throws Exception
    {
        FileList result;
        while(true)
        {
            try
            {
                result = this.driveService.files().list()
                        .setQ(query)
                        .setPageSize(20)
                        .setFields("nextPageToken, files(id, name, parents, mimeType, size)")
                        .execute();
                break;
            }
            catch (SocketTimeoutException e)
            {
                System.out.printf("Connection timed out\n");
            }
        }
        /*FileList result = this.driveService.files().list()
                .setQ(query)
                .setPageSize(20)
                .setFields("nextPageToken, files(id, name, parents, mimeType, size)")
                .execute();
          */
        String pageToken = result.getNextPageToken();
        List<com.google.api.services.drive.model.File> files_list = result.getFiles();

        while(pageToken!=null){
            while(true)
            {
                try
                {
                    result = this.driveService.files().list()
                            .setQ(query)
                            .setPageSize(20)
                            .setFields("nextPageToken, files(id, name, parents, mimeType, size)")
                            .setPageToken(pageToken)
                            .execute();
                    break;
                }
                catch (SocketTimeoutException e)
                {
                    System.out.printf("Connection timed out\n");
                }
            }
            pageToken = result.getNextPageToken();
            files_list.addAll(result.getFiles());
        }

        //Logger logger = Logger.getLogger("Query Logger");
        //logger.info("Query Complete\n");
        return files_list;
    }

    private void fillContents(FolderObject creating) throws Exception
    {
        HashSet<GTuple> tmp_folders = new HashSet<>();
        HashSet<GTuple> tmp_files = new HashSet<>();
        tmp_folders.clear();
        tmp_files.clear();

        String query = "'"+creating.folder_id+"' in parents";
        List<com.google.api.services.drive.model.File> files_list = this.executeQuery(query);

        for (com.google.api.services.drive.model.File file : files_list) {
            if(file.getMimeType().equals("application/vnd.google-apps.folder")) {
                tmp_folders.add(new GTuple(file.getId(),file.getName()));
            }
            else {
                tmp_files.add(new GTuple(file.getId(),file.getName()));
            }
        }

        creating.contentFolders.addAll(tmp_folders);
        creating.contentFiles.addAll(tmp_files);
    }

    public void generateFileCache(boolean debug_mode) throws Exception
    {
	    System.out.printf("Generating Root Node...\n");
	    FolderObject root_directory = this.generateRootDirectoryNode();

	    if(debug_mode){
		    System.out.printf("Root Node contains (2nd level files):\n");
		    for (GTuple file : root_directory.contentFiles) {
			    System.out.println(file.name+" "+file.id);
		    }

		    for (GTuple folder : root_directory.contentFolders) {
			    System.out.println(folder.name+" "+folder.id);
		    }
		    System.out.printf("\n");
	    }

	    System.out.println("Constructing Higher Levels...\n");

	    int level_count;
	    HashMap<String,FolderObject> first_level = new HashMap<>();
	    first_level.put(root_directory.full_path,root_directory);

	    HashMap<String,FolderObject> second_level_folders = this.buildNextLevel_Folder(first_level);
	    HashMap<String,FileObject> second_level_files = this.buildNextLevel_File(first_level);

	    all_folder_nodes.put(root_directory.full_path,root_directory);
	    if(second_level_folders!=null)
	    {
		    all_folder_nodes.putAll(second_level_folders);
	    }
	    if(second_level_files!=null)
	    {
		    all_file_nodes.putAll(second_level_files);
	    }

	    level_count=1;
	    if(debug_mode){
	        System.out.printf("Level %d:\n",level_count);
		    if(second_level_folders!=null)
		    {
			    for (String key : second_level_folders.keySet())
			    {
				    FolderObject tmp = second_level_folders.get(key);
				    System.out.println(tmp.folder_name+" "+tmp.folder_id+":\n"+tmp.full_path);
			    }
		    }

		    if(second_level_files!=null)
		    {
			    for (String key : second_level_files.keySet())
			    {
				    FileObject tmp = second_level_files.get(key);
				    System.out.println(tmp.full_path);
			    }
		    }
		    System.out.printf("\n");
	    }
	    level_count++;


	    while(second_level_folders!=null)
	    {
		    first_level.clear();
		    first_level.putAll(second_level_folders);
		    second_level_folders = this.buildNextLevel_Folder(first_level);
		    second_level_files = this.buildNextLevel_File(first_level);

		    if(second_level_folders!=null)
		    {
			    all_folder_nodes.putAll(second_level_folders);
		    }
		    if(second_level_files!=null)
		    {
			    all_file_nodes.putAll(second_level_files);
		    }

		    if(debug_mode){
			    System.out.printf("Level %d:\n",level_count);
			    if(second_level_folders!=null)
			    {
				    for (String key : second_level_folders.keySet())
				    {
					    FolderObject tmp = second_level_folders.get(key);
					    System.out.println(tmp.full_path);
				    }
			    }

			    if(second_level_files!=null)
			    {
				    for (String key : second_level_files.keySet())
				    {
					    FileObject tmp = second_level_files.get(key);
					    System.out.println(tmp.full_path);
				    }
			    }
			    System.out.printf("\n");
		    }
		    level_count++;
		    System.out.printf("Level %d completed\n",level_count);
	    }

	    FileOutputStream node_storage_stream = new FileOutputStream(node_storage_file);
	    ObjectOutputStream node_out_obj = new ObjectOutputStream(node_storage_stream);
	    node_out_obj.writeObject(all_folder_nodes);
	    node_out_obj.writeObject(all_file_nodes);
	    node_out_obj.close();
	    node_storage_stream.close();
    }

	public void showContent(String current_path, String cmd) {
		FolderObject cur_folder = all_folder_nodes.get(current_path);
		if(cmd.equals("ls"))
		{
			for(GTuple tmp : cur_folder.contentFolders)
			{
					System.out.println(tmp.name);
			}
			for(GTuple tmp : cur_folder.contentFiles)
			{
				String full_path = current_path+tmp.name;
				FileObject tmp_file = all_file_nodes.get(full_path);
				String size_human_readable = generateReadableSize(tmp_file.size);
				System.out.println(tmp_file.file_name+" "+size_human_readable);
			}
		}

		if(cmd.equals("ls -u"))
		{
			for(GTuple tmp : cur_folder.contentFolders)
			{
				System.out.println(tmp.name+" "+tmp.id);
			}
			for(GTuple tmp : cur_folder.contentFiles)
			{
				String full_path = current_path+tmp.name;
				FileObject tmp_file = all_file_nodes.get(full_path);
				System.out.println(tmp_file.file_name+" "+tmp_file.file_id);
			}
		}
	}

	private String generateReadableSize(double size)
	{
    	String res;
    	int measurement_scope=0; //Bytes
		String[] measures = new String("B KB MB GB TB").split(" ");

		while(size>1024)
		{
			size=size/1024;
			measurement_scope++;
		}

		DecimalFormat decimalFormat= new DecimalFormat("#.00");
		res = decimalFormat.format(size)+measures[measurement_scope];
		return res;
	}


	public String switchDirectory(String current_path, String cmd) {
    	cmd=cmd.replaceFirst("cd\\s+","cd ");
    	String[] cmd_tokens = cmd.split(" ");
		int i;
		String res="";
		String folder_name="";
		String full_path;

		if(cmd_tokens[1].equals("."))
			return current_path;
		if(cmd_tokens[1].equals("..")){
			String[] path_tokens = current_path.split("/");
			for(i=1;i<path_tokens.length-1;i++)
			{
				if(path_tokens[i].length()>0)
					res=res+"/"+path_tokens[i];
			}
			res=res+"/";
			System.out.println(res);
			return res;
		}
		if(cmd_tokens[1].equals("..."))
			return "/";

		FolderObject cur_folder = all_folder_nodes.get(current_path);
		folder_name=cmd_tokens[1];
		folder_name=folder_name.replaceAll("\\\\","/");
		for(i=2;i<cmd_tokens.length;i++)
		{
			folder_name=folder_name+" "+cmd_tokens[i];
		}
		/*for(GTuple tmp : cur_folder.contentFolders)
		{
			if(tmp.name.equals(folder_name))
			{
				res=current_path+tmp.name+"/";
				System.out.println(res);
				return res;
			}
		}*/

		full_path=current_path+folder_name;
		if(!full_path.endsWith("/"))
			full_path=full_path+"/";

		FolderObject tmp_folder = all_folder_nodes.get(full_path);
		if(tmp_folder!=null)
		{
			current_path=tmp_folder.full_path;
			System.out.println(current_path);
		}
		else
		{
			System.out.printf("No such folder.\n");
		}

		return current_path;
	}

	public void downloadFile(String file_id) throws Exception
	{
    	//Assuming all file ids are valid.

		OutputStream file_output_stream = new FileOutputStream(System.getProperty("user.home")+"/G-Mapper/tester1.file");
		driveService.files().get(file_id).executeMediaAndDownloadTo(file_output_stream);
	}

	public void downloadFile_all(String download_queue_path, String storage_prefix) throws IOException
	{
		//Assuming all file ids are valid.
		FileReader fileReader = new FileReader(download_queue_path);
		BufferedReader bufferedReader = new BufferedReader(fileReader);

		String line;
		while((line=bufferedReader.readLine())!=null)
		{
			int i;
			//full_path*id
			String[] tokens = line.split("\\*");
			String full_path = tokens[0];
			String file_id = tokens[1];

			tokens = full_path.split("/");
			String file_name = tokens[tokens.length-1];
			String file_path="";

			Date date = new Date();
			SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			System.out.println("["+simpleDateFormat.format(date)+"]: Started downloading "+full_path);

			for(i=0;i<tokens.length-1;i++)
			{
				if(tokens[i].length()>0)
					file_path=file_path+"/"+tokens[i];
			}

			/*Process p = Runtime.getRuntime().exec("mkdir -p "+file_path);
			p.waitFor();
			int exitStatus = p.exitValue();

			if(p.exitValue()!=0) {
				System.out.println("Error creating storage folder(s). Exiting...");
				exit(1);
			}*/

			File storage_dir = new File((storage_prefix+file_path).replaceAll("//","/"));
			if (!storage_dir.exists())
			{
				if(!storage_dir.mkdirs())
				{
					System.out.println("["+simpleDateFormat.format(date)+"]: Error downloading "+storage_prefix+file_path);
					System.out.println("["+simpleDateFormat.format(date)+"]: Unable to create storage folder(s).");
					failed_list.add(storage_prefix+file_path+"/"+file_name);
					System.out.println("["+simpleDateFormat.format(date)+"]: This error has been logged");

					continue;
				}
			}

			OutputStream file_output_stream = new FileOutputStream(storage_prefix+file_path+"/"+file_name);
			while(true)
			{
				try{
					driveService.files().get(file_id).executeMediaAndDownloadTo(file_output_stream);
					break;
				}
				catch (SocketTimeoutException e) {
					System.out.println(simpleDateFormat.format(date)+": Connection timed out.");
				}
			}

		}
	}

	public void addToDownloadQueue(String current_path, String cmd) {
		String[] cmd_tokens = cmd.split(" ");
		int i;

		String target_name=cmd_tokens[1];
		for(i=2;i<cmd_tokens.length;i++)
		{
			target_name=target_name+" "+cmd_tokens[i];
		}

		ArrayList<FolderObject> cur_level = new ArrayList<>();
		ArrayList<FolderObject> next_level = new ArrayList<>();
		HashSet<FileObject> download_queue_new = new HashSet<>();

		if(all_folder_nodes.get(current_path+target_name+"/")!=null)
		{

			FolderObject target_folder;
			cur_level.add(all_folder_nodes.get(current_path+target_name+"/"));

			while(!cur_level.isEmpty()){
				for(i=0;i<cur_level.size();i++)
				{
					target_folder=cur_level.get(i);
					for(GTuple tmp : target_folder.contentFiles)
					{
						FileObject tmp_file = all_file_nodes.get(target_folder.full_path+tmp.name);
						download_queue.add(tmp_file);
						download_queue_new.add(tmp_file);

						total_download_items++;
						total_download_size=total_download_size+tmp_file.size;
					}

					for(GTuple tmp : target_folder.contentFolders)
					{
						next_level.add(all_folder_nodes.get(target_folder.full_path+tmp.name+"/"));
					}
				}

				cur_level.clear();
				cur_level.addAll(next_level);
				next_level.clear();
			}
		}
		else if(all_file_nodes.get(current_path+target_name)!=null)
		{
			FileObject target_file = all_file_nodes.get(current_path+target_name);
			download_queue_new.add(target_file);
		}
		else
		{
			System.out.println("No such file or directory.");
		}

		System.out.println("The following files have been added to download queue:");
		for(FileObject tmp : download_queue_new)
		{
			System.out.println(tmp.full_path+" "+this.generateReadableSize(tmp.size));
		}


		System.out.println("-------------------------------------------");
		System.out.println("Total "+total_download_items+" download items in queue.");
		System.out.println("Total size: "+generateReadableSize(total_download_size));
	}

	public void exportDownloadQueue() throws Exception
	{
    	FileWriter download_queue_file = new FileWriter(System.getProperty("user.home")+"/G-Mapper/edq.xr");
    	BufferedWriter download_queue_writer = new BufferedWriter(download_queue_file);
		for(FileObject tmp : download_queue)
		{
			download_queue_writer.write(tmp.full_path+"*"+tmp.file_id+"\n");
		}
		download_queue_writer.close();
		download_queue_file.close();
		System.out.println("Download queue stored to "+System.getProperty("user.home")+"/G-Mapper/edq.xr");
	}

	public void downloadAllByExt(String cmd) {
    	String current_path = "/";
		String[] cmd_tokens = cmd.split(" ");
		int i;

		String ext = "."+cmd_tokens[1];

		ArrayList<FolderObject> cur_level = new ArrayList<>();
		ArrayList<FolderObject> next_level = new ArrayList<>();

		FolderObject target_folder;
		cur_level.add(all_folder_nodes.get(current_path));
		download_queue.clear();

		while(!cur_level.isEmpty()){
			for(i=0;i<cur_level.size();i++)
			{
				target_folder=cur_level.get(i);
				for(GTuple tmp : target_folder.contentFiles)
				{
					if(tmp.name.endsWith(ext))
					{
						FileObject tmp_file = all_file_nodes.get(target_folder.full_path+tmp.name);
						download_queue.add(tmp_file);

						total_download_items++;
						total_download_size=total_download_size+tmp_file.size;
					}
				}

				for(GTuple tmp : target_folder.contentFolders)
				{
					next_level.add(all_folder_nodes.get(target_folder.full_path+tmp.name+"/"));
				}
			}

			cur_level.clear();
			cur_level.addAll(next_level);
			next_level.clear();
		}

		System.out.println("The following files have been added to download queue:");
		for(FileObject tmp : download_queue)
		{
			System.out.println(tmp.full_path+" "+this.generateReadableSize(tmp.size));
		}

		System.out.println("-------------------------------------------");
		System.out.println("Total "+total_download_items+" download items in queue.");
		System.out.println("Total size: "+generateReadableSize(total_download_size));
	}
}
