package utils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.core.ZipFile;

/**
 * @author JBender
 * Scratch Extractor identifies Scratch blocks serialized as JSON and strips irrelevant 
 * syntax while preserving hierarchical structure.
 */
public class ScratchExtractor {
	private Path scratchDir;
	private Path scratchJsonDir;
	private Path outputDir;
	private TreeMap<Integer, ArrayList<Tree<Block>>> userProjects;
	
	private static final String OBJECT = "\"objName\": \"";
	private static final String CHILDREN = "\"children\": ";
	private static final String SCRIPTS = "\"scripts\": ";
	private static final char O_SCOPE = '[';
	private static final char C_SCOPE = ']';
	
	public ScratchExtractor(Path scratchDir, Path outputDir) throws IOException {
	    this.scratchDir = scratchDir;
	    this.outputDir = outputDir;
	    this.scratchJsonDir = Paths.get(scratchDir + "-tmp");
	    Files.createDirectories(scratchJsonDir);
	    this.userProjects = new TreeMap<Integer, ArrayList<Tree<Block>>>();
	}
	
	/**
	 * Decompress all .sb2 files, identify JSON files, and initiate transformation to .se.
	 */
	private void extractDirectory(Path path) throws IOException {
		long count = 0;
		Path outputPath = Paths.get(scratchJsonDir.toString(), "/", path.getFileName().toString());
		Path filePath = null;
		String projectName;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(path, "*.sb2")) {
			for (Path sb2File : stream) {
				if (count == 0)
					Files.createDirectories(outputPath);
				projectName = sb2File.getFileName().toString();
				filePath = Paths.get(outputPath.toString(), projectName);
	  	        Files.createDirectories(filePath);
				unzip(sb2File, filePath);
				++count;
				try (DirectoryStream<Path> streamJson = Files.newDirectoryStream(filePath, "*.json")) {
					for (Path jsonFile : streamJson) {
						extractFile(jsonFile, Integer.parseInt(outputPath.getFileName().toString()), projectName);
					}
				}	
			}
		}
	}
	  
	/**
	 * Parse JSON to build an in-memory representation of Scratch objects and blocks
	 */
  	protected void extractFile(Path jsonFile, int userID, String projectName) {
  		
  		try (BufferedReader reader = Files.newBufferedReader(jsonFile, StandardCharsets.UTF_8)) {
  			Tree<Block> project = null;
  			String objName = null;
  			Block parentObject = null;
  			Block parentBlock = null;
  			Block currentBlock = null;
  			Boolean isInScript = false;
  			StringBuilder objScript = new StringBuilder();
  			int sOpen;
  			int sClose;
  			String line = null;
  	      
  			while ((line = reader.readLine()) != null) {
  				if (line.contains(OBJECT))
  				{
  					objName = line.substring(line.indexOf(OBJECT) + OBJECT.length(), 
  						line.lastIndexOf('"'));
  					currentBlock = new Block(objName, null);
  					if (project == null) 
  						project = new Tree<Block>(currentBlock, projectName);
  					else
  						project.addLeaf(parentObject, currentBlock);
  					parentBlock = currentBlock;
  					objScript = new StringBuilder();
  				}
  				else if (line.contains(CHILDREN))
  					parentObject = currentBlock;
  				else if (line.contains(SCRIPTS))
  				{
  					isInScript = true;
  					objScript.append(line.substring(line.indexOf(SCRIPTS) + SCRIPTS.length(),
  							line.length()));
  				}
  	    	  
  				if (isInScript)
  				{
  					if(!line.contains(SCRIPTS))
  						objScript.append(line);
  					Iterator<Integer> iterator = objScript.chars().iterator();
  					sOpen = sClose = 0;
  					char c[];
  	    		    // search for matching count of [] chars, indicating end of scripts
  					while (iterator.hasNext())
  					{
  						c = Character.toChars(iterator.next());
  						if (c[0] == O_SCOPE)
  							++sOpen;
  						else if (c[0] == C_SCOPE)
  							++sClose;
  					}
  					if (sOpen != 0 && sOpen == sClose)
  					{
  						addBlocks(project, parentBlock, objScript);
  						isInScript = false;
  					}
  				}  	    	  
  			}
  	      
		if(project != null)
			addProject(userID, project);
      
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
  	
  	/**
  	 * Parse and load into memory the blocks specified in JSON "Scripts:" sections
  	 */
  	private void addBlocks(Tree<Block> project, Block parentBlock, StringBuilder objScript)
  	{
  		StringBuilder blockName = null;
  		Boolean isInBlockName = false;
  		Boolean isPrimitive = false;
  		int blockOpenCount = 0;
  		Iterator<Integer> iterator = objScript.chars().iterator();
		int sOpen, sClose;
		sOpen = sClose = 0;
		Block currentBlock = null;
		
		char c[];
		char prevChar = 0;
		// search for matching count of [] chars, indicating end of scripts
		while (iterator.hasNext())
		{
			c = Character.toChars(iterator.next());
			switch (c[0]) {
				case O_SCOPE:
					++sOpen;
					// place primitives such as '=' on same level
					isPrimitive = parentBlock != null && parentBlock.getBlockName() != null && parentBlock.getBlockName().length() > 1;
					if (blockOpenCount > 0 && !isPrimitive)
						parentBlock = currentBlock;
					break;
				case C_SCOPE:
					++sClose;
					if (blockOpenCount > 0 && !isPrimitive)
					{
						if(currentBlock.getBlockName() != null) // already at Object level
							currentBlock = project.getTree(currentBlock).getParent().getHead();
						if(currentBlock.getBlockName() != null) // already at Object level
							parentBlock = project.getTree(currentBlock).getParent().getHead();
						--blockOpenCount;
					}
					else
						isPrimitive = false;
					break;
				case '\"': 
					if (prevChar == O_SCOPE)
					{
						blockName = new StringBuilder();
						isInBlockName = true;
						++blockOpenCount;
					}
					else if (isInBlockName)
					{
						isInBlockName = false;
						currentBlock = new Block(blockName.toString());
						project.addLeaf(parentBlock, currentBlock);
					}
					break;
				default:
					if(isInBlockName)
						blockName.append(c[0]);
					break;
			}
			
			if(sOpen != 0 & sOpen == sClose)
				break;
			prevChar = c[0];
		}
  		
  	}
  	
  	/**
  	 * Add a project to the per-user ArrayList of projects in memory
  	 */
  	private void addProject(int userID, Tree<Block> project)
  	{
  		if(userProjects.containsKey(userID))
  			userProjects.get(userID).add(project);
  		else
  		{
  			ArrayList<Tree<Block>> al = new ArrayList<Tree<Block>>();
  			al.add(project);
  			userProjects.put(userID, al);
  		}
  	}
  	
  	/**
  	 * Decompress a .sb2 file
  	 */
  	private void unzip(Path sb2File, Path outputPath) {

  	    try {
  	         ZipFile zipFile = new ZipFile(sb2File.toString());
  	         zipFile.extractAll(outputPath.toString());
  	    } catch (ZipException e) {
  	        e.printStackTrace();
  	    }
  	}
  	
  	/**
  	 * Transform the per-user ArrayList of projects into .se files written to per-project files in 
  	 * per-user subdirectories in the file system
  	 */
  	private void writeProjects() throws IOException {
		Writer writer = null;
		Set<Entry<Integer, ArrayList<Tree<Block>>>> set = userProjects.entrySet();
		Iterator<Map.Entry<Integer, ArrayList<Tree<Block>>>> iterator = set.iterator();
		while (iterator.hasNext())
		{
			Map.Entry<Integer, ArrayList<Tree<Block>>> me = (Map.Entry<Integer, ArrayList<Tree<Block>>>)iterator.next();
			Path userPath = Paths.get(outputDir.toString(), "/", me.getKey().toString());
			Files.createDirectories(userPath);
			ArrayList<Tree<Block>> al = me.getValue();
			for (Tree<Block> project : al)
			{
				try {
				    writer = new BufferedWriter(new OutputStreamWriter(
				          new FileOutputStream(Paths.get(userPath.toString(), project.getName()).toString().replaceFirst(".sb2", ".se")), "utf-8"));
				    writer.write(project.printTree(1));
				} catch (IOException ex) {
					
				} finally {
				   try {writer.close();} catch (Exception ex) {}
				}
				
			}
		}
		
		
	}
  	
	/**
	 * Test method used for debugging tree-building
	 */
	private void printProjects()
	{
		Set<Entry<Integer, ArrayList<Tree<Block>>>> set = userProjects.entrySet();
		Iterator<Map.Entry<Integer, ArrayList<Tree<Block>>>> iterator = set.iterator();
		while (iterator.hasNext())
		{
			Map.Entry<Integer, ArrayList<Tree<Block>>> me = (Map.Entry<Integer, ArrayList<Tree<Block>>>)iterator.next();
			ArrayList<Tree<Block>> al = me.getValue();
			for (Tree<Block> project : al)
			{
				System.out.print(project.printTree(1) + "\n\n");
			}
		}
	}
	
	/**
	 * Primary entry. Extract each subdirectory.
	 */
	public void extract() throws IOException {
		
		 DirectoryStream.Filter<Path> filt = new DirectoryStream.Filter<Path>() {
		        @Override
		        public boolean accept(Path file) throws IOException {
		            return (Files.isDirectory(file));
		        }
		    };

		    try (DirectoryStream<Path> stream = Files.newDirectoryStream(scratchDir, filt)) {
		        for (Path path : stream) {
		            extractDirectory(path);
		        }
		    } catch (IOException e) {
		        e.printStackTrace();
		    }
		    writeProjects();
	}
	
	/**
	 * Program entry.
	 * Usage: java -classpath <Path to zip4j;...> utils.ScratchExtractor <Path to Scratch sb2 files> <Output Path>
	 */
	public static void main(String[] args) throws Exception {

		if (args.length != 2) {
			usage("Wrong number of arguments ("+args.length+")");
		    return;
		}
		Path scratchDir = Paths.get(args[0]);
		if (!Files.exists(scratchDir)) {
		    usage("Cannot find Path to Scratch sb2 files ("+scratchDir+")");
		    return;
		}
		Path outputDir = Paths.get(args[1] + "-tmp");
	    Path finalDir = Paths.get(args[1]);
	    Files.createDirectories(outputDir);
	    System.out.println("Deleting all files in " + finalDir);
	    IOUtils.rm(finalDir);
	    ScratchExtractor extractor = new ScratchExtractor(scratchDir, outputDir);
	    extractor.extract();
	    Files.move(outputDir, finalDir, StandardCopyOption.ATOMIC_MOVE);
	    IOUtils.rm(extractor.scratchJsonDir);
	}

	/**
	 * Print usage instructions
	 */
	private static void usage(String msg) {
	    System.err.println("Usage: "+msg+" :: java -classpath <...> utils.ScratchExtractor <Path to Scratch sb2 files> <Output Path>");
	  }
}
