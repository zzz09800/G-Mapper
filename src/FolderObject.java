import java.io.Serializable;
import java.util.HashSet;

/**
 * Created by andrew on 7/24/17.
 */
public class FolderObject implements Serializable {
    String folder_name;
    String folder_id;
    int folder_level;
    String full_path;

    HashSet<GTuple> contentFolders;
    HashSet<GTuple> contentFiles;

    public FolderObject(String name, String id, int level) {
        this.folder_name=name;
        this.folder_id=id;
        this.folder_level=level;
        this.contentFiles=new HashSet<>();
        this.contentFolders=new HashSet<>();
        this.full_path=null;
    }

    public FolderObject(String name, String id, int level, String path) {
        this.folder_name=name;
        this.folder_id=id;
        this.folder_level=level;
        this.contentFiles=new HashSet<>();
        this.contentFolders=new HashSet<>();
        this.full_path=path;
    }

    public FolderObject() {
        this.contentFiles=new HashSet<>();
        this.contentFolders=new HashSet<>();
    }
}
