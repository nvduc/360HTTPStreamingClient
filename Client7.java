import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Client3 {
	
	/* Global Variables */
	public static int SEG_LEN = 32;        /* Number of frames per segment */
	public static double VIEWPORT_SIZE = 960 * 960;
	public static int N_frame = 1792;      /* Number of frames */
	public static int N_seg = 56;          /* Number of segment (adaptation intervals) */    
	public static int N_tile = 64;         /* Number of tiles */
	public static int N_version = 9;       /* Number of versions */
	public static double[][][] tile_BR = new double[N_seg][N_tile][N_version];          /* Tiles' bitrates */
	public static double[][][] tile_PSNR = new double[N_seg][N_tile][N_version];        /* Tiles' PSNR */
	public static double[][][] tile_D = new double[N_seg][N_tile][N_version];           /* Tiles' Distortion */
	public static double [][][]   vmask  = new double[360][181][N_tile];                /* Tiles' Visible Mask */
	public static int [][] head_trace = new int[N_frame][2];                            /* Head trace */
	public static double[] bandwidth_trace=new double[200];
	public static double[] time_trace=new double [200];
	public static int TILE_VERSION_SELECT_METHOD = 2; // 1: EQUAL; 2: DirectRoI
	public static int THRP_ADAPT_METHOD = 1;          // 0: Baseline; 1: Proposed
	public static double thres = 1.0;                 // threshold
	
	public static int QP_list[] = {50, 48, 44, 40, 36, 32, 28, 24, 20}; /* QP cang thap thi chat luong cang cao */
	public static double[] buffer_time_seg = new double[N_seg];
	public static double[] buffer_size_seg = new double[N_seg];
	/* socket */
	public static PrintWriter out;
	public static BufferedInputStream in;
	public static double[][] tile_down_thrp = new double[N_seg][N_tile];          /* Tiles' download througphut */
	
  public static void loadInputData() throws IOException {
	  
	  // load tiles's bitrates, PSNR, Distortion
	  BufferedReader reader;
	  int seg_id, tile_id, frame_id, i;
	  int phi, theta;
	  String line;
	  
	  for(tile_id = 0; tile_id < N_tile; tile_id ++) {
		  reader = new BufferedReader(new FileReader("tile_info/tile_" + tile_id + ".txt"));
		  line = reader.readLine();
		  seg_id = 0;
		  while (line != null) {
			  String[] splitted = line.split("\t");
			  for(i = 0; i < N_version; i++) {
				  tile_BR[seg_id][tile_id][i] = Double.parseDouble(splitted[i*3]);
				  tile_PSNR[seg_id][tile_id][i] = Double.parseDouble(splitted[i*3+1]);
				  tile_D[seg_id][tile_id][i] = Double.parseDouble(splitted[i*3+2]);
			  }
			  seg_id ++;
			  // read next line
			  line = reader.readLine();
			}
		  reader.close();
	  }
	  
	  
	 // load visible mask
	 reader = new BufferedReader(new FileReader("visible_mask_8x8_FoV_90.txt"));
	 line = reader.readLine();
	 while(line != null) {
		 String[] splitted = line.split("\t");
		 phi = Integer.parseInt(splitted[0]);
		 theta = Integer.parseInt(splitted[1]) + 90;
		 for(tile_id = 0; tile_id < N_tile; tile_id ++) {
			 vmask[phi][theta][tile_id] = Integer.parseInt(splitted[2 + tile_id*2 + 1]) * 1.0 / VIEWPORT_SIZE;
		 }
		 line = reader.readLine();
	 }
	 reader.close();
	 
	 // load head trace
	 reader = new BufferedReader(new FileReader("head_trace/xyz_vid_0_uid_0.txt"));
	 line = reader.readLine();
	 frame_id = 0;
	 while(frame_id < N_frame && line != null) {
		 String[] splitted = line.split("\t");
		 head_trace[frame_id][0]= (int) (Double.parseDouble(splitted[0]) + 180);
		 head_trace[frame_id][1]= (int) (Double.parseDouble(splitted[1]) + 90);
		 frame_id ++;
		 line = reader.readLine();
	 }
	 reader.close();
	 
	 // load bandwidth trace
	 reader = new BufferedReader(new FileReader("ngan/Bandwidth_trace.txt"));
	 line = reader.readLine();
	 frame_id = 0;
	 while(frame_id < 202 && line != null) {
		 String[] splitted = line.split("\t");
		 time_trace[frame_id]= (double) (Double.parseDouble(splitted[0]));
		 bandwidth_trace[frame_id]= (double) (Double.parseDouble(splitted[1]));
		 frame_id ++;
		 line = reader.readLine();
	 }
	 reader.close();
//	 for(int k=0;k<200;k++) {
//		 System.out.println(bandwidth_trace[k]);
//	 }
//	 for(int k=0;k<200;k++) {
//		 System.out.println(time_trace[k]);
//	 }
  }
	 

  public static void main(String[] args) throws Exception {
	loadInputData();

	
	
    InetAddress addr = InetAddress.getByName("192.168.1.100");
    Socket socket = new Socket(addr, 80);
    boolean autoflush = true;
    
	out = new PrintWriter(socket.getOutputStream(), autoflush);
    in = new BufferedInputStream(socket.getInputStream());
    
    double RC; /* kbit/s */
    
    int N_version = 9;
    String tile_id_str = "";

    //int seg_id = 0; /* segment dau tien */
    RC = bandwidth_trace[0];
	double t_sum=0;
    //double thrp[] = {5000, 3000, 2500,3000,4000}; // throughput for every 0.5 seconds (RC)
    //double time[] = {2.0,4.0, 6.0,8.0,15.0}; // thoi gian theo giay  double next_thrp = 0;
	double next_thrp=0;
	double delay_time = 0; // time until the next request
	
	String filename = "result_METHOD_" + THRP_ADAPT_METHOD + ".txt";
	PrintWriter log_file = new PrintWriter(new FileWriter(filename));
	log_file.printf("Time,segment_id,seg_down_thrp,RC,buffer_size,seg_down_time,buff_time,viewport_quality\n");

	double buffer_time_1 = 0;
	int index = 0;
	int N_seg_1 = N_seg;
	int N_seg_2 = 200;
	int seg_id_2 = N_seg_2;
	while(seg_id_2 > 0) {
		seg_id_2 = seg_id_2 - 56;
		index = index + 1;
	}
	for(int index_1 = 0; index_1 <index ; index_1++) {
    if(index_1 == index -1) {
    	N_seg_1 = N_seg_2;
    }
    for(int seg_id = 0; seg_id < N_seg_1; seg_id++) {
    	System.out.println("----------------------------------------------------------");
    	System.out.println("segment: "+ seg_id);
    	
		int[] tile_version = new int[N_tile]; // version cua tung tile
	    double sum_br;
	    double sum_tile_BR = 0;
	    double sum_visible_br;
	    double sum_invisible_br;
	    int i,j, tile_id, version_id=0;
	    int phi, theta; // phi: kinh do, theta: vi do
	    double tile_weight[] = new double[N_tile];
	    List<Integer> visible_tile_rear_list =new ArrayList<Integer>();
	    List<Integer> invisible_tile_rear_list =new ArrayList<Integer>();
		double seg_download_time = 0, tile_download_time = 0, tile_download_thrp=0, t_now = 0, tile_size = 0, pre_title_download_thrp = 0;
	    //--------------- Direct RoI Method ------------------------//
	    if(TILE_VERSION_SELECT_METHOD == 2) {
	    // xac dinh danh sach cac tile nguoi dung nhin thay
	    List<Integer> visible_tile_list =new ArrayList<Integer>();
	    List<Integer> invisible_tile_list =new ArrayList<Integer>();
	    phi = head_trace[seg_id*SEG_LEN][0];
	    theta = head_trace[seg_id * SEG_LEN][1];
	    tile_weight = vmask[phi][theta];
	  //in ra tile_weight
	    tile_weight = vmask[phi][theta];
	    for(tile_id=0; tile_id < N_tile; tile_id++) {
			System.out.printf("%.2f ", vmask[phi][theta][tile_id]);
			if((tile_id+1)%8 == 0)
				System.out.printf("\n");
		}
	    System.out.println("\n");
	    for(tile_id=0; tile_id < N_tile; tile_id++) {
			System.out.printf("%f ", tile_weight[tile_id]);
			if((tile_id+1)%8 == 0)
				System.out.printf("\n");
		}
	    // so sanh tile_weight[tile_id] voi 0, neu tile_weight[tile_id] > 0 --> visible tile
	    for(tile_id=0; tile_id < N_tile; tile_id ++) {
	    	if(tile_weight[tile_id] > 0)
	    		visible_tile_list.add(tile_id);
	    	else
	    		invisible_tile_list.add(tile_id);
	    }
	    
	    // quyet dinh version cho invisible tiles -> gan bang 0
	    for(tile_id=0; tile_id < invisible_tile_list.size(); tile_id++) {
	    	tile_version[invisible_tile_list.get(tile_id)] = 0;
	    }
	    
	    // Update gia tri cua RC: RC = RC - sum_br(invisible tile)
	    // tinh tong bitrate cua tat ca cac invisible_tile
	    sum_invisible_br = 0;
	    for(tile_id = 0; tile_id < invisible_tile_list.size(); tile_id ++) {
			sum_invisible_br += tile_BR[seg_id][ invisible_tile_list.get(tile_id)][0]; 
	    }	
	    double RC_2 = RC - sum_invisible_br;
	    // quyet dinh version cho visible tiles: cao nhat co the
	    for(version_id = 0; version_id < N_version; version_id++) {
	        // tinh tong bitrate cua tat ca cac visible_tile
	        	sum_visible_br = 0;
	        	for(tile_id = 0; tile_id < visible_tile_list.size(); tile_id ++)
	        		sum_visible_br += tile_BR[seg_id][visible_tile_list.get(tile_id)][version_id]; 
	        	if(sum_visible_br > RC_2)
	        		break;
	        }
	    if(version_id == 0)
	    	version_id ++;
	        for(tile_id=0; tile_id < visible_tile_list.size(); tile_id++) 
	        	tile_version[visible_tile_list.get(tile_id)] = version_id -1;
	        System.out.println(version_id - 1);
	   
	    
	    //--------------- Direct RoI Method ------------------------//
	    }
	//    for(tile_id = 0; tile_id < N_tile; tile_id ++) {
	//  		System.out.println(tile_version[tile_id]);
	//      }	
	    
	    if(TILE_VERSION_SELECT_METHOD == 1) {
	    //--------------------- EQUAL Method --------------------------//
	    // quyet dinh verion cua tung tile (EQUAL)
	    for(version_id = 0; version_id < N_version; version_id++) {
	    	// tinh tong bitrate cua tat ca cac tile
	    	sum_br = 0;
	    	for(tile_id = 0; tile_id < N_tile; tile_id ++)
	    		sum_br += tile_BR[seg_id][tile_id][version_id];
	    	if(sum_br > RC)
	    		break;
	    }
	    
	    for(tile_id=0; tile_id < N_tile; tile_id++)
	    	tile_version[tile_id] = version_id - 1;
	    	
	    //--------------------- EQUAL Method --------------------------//
	    
	    }
	    System.out.printf("Tile Version:\n");
	    for(tile_id = 0; tile_id < N_tile; tile_id ++) {
	  		System.out.printf("%d ", tile_version[tile_id]);
	  		if((tile_id + 1)%8 == 0)
	  			System.out.printf("\n");
	    }	
	    
	  
	    for(tile_id=0; tile_id < N_tile; tile_id++) {
			
	    	// update tile's versions
			if (THRP_ADAPT_METHOD == 1) {
				if(Math.round(tile_download_thrp) < Math.round(pre_title_download_thrp) || (tile_id ==1 && tile_download_thrp < RC)) {
				visible_tile_rear_list.clear();
				for (int tile_rear_id = tile_id ; tile_rear_id < N_tile; tile_rear_id++) {
					if (tile_version[tile_rear_id] != 0) {
						visible_tile_rear_list.add(tile_rear_id);
					} 
				}
				int ver; 
				if(visible_tile_rear_list.size() > 0 && tile_version[visible_tile_rear_list.get(0)] != 0 ) {
								
					for (ver = tile_version[visible_tile_rear_list.get(0)]; ver > -1; ver--) {
						for (int tile_rear_id = 0; tile_rear_id < visible_tile_rear_list.size(); tile_rear_id++) {
							tile_version[visible_tile_rear_list.get(tile_rear_id)] = ver;
						}
						// tinh tong bitrate cac tile con lai
						double sum_rear_br = 0;
						for (int tile_rear_id = tile_id+1; tile_rear_id < N_tile; tile_rear_id++) {
							sum_rear_br += tile_BR[seg_id][tile_rear_id][tile_version[tile_rear_id]];
						}
	//					System.out.println(sum_rear_br);
						// tinh timedownload uoc tinh
						double sum_timedownload_rear = 0;
						sum_timedownload_rear = sum_rear_br / tile_download_thrp; // tile_download_thrp x (1 - 0.2)
						System.out.println(t_now + tile_download_time + sum_timedownload_rear);
						System.out.println(ver);
						if (t_now + tile_download_time + sum_timedownload_rear < 1 || ver == 0) 
							break;			
					}
					for (int tile_rear_id = 0; tile_rear_id < visible_tile_rear_list.size(); tile_rear_id++) {
						tile_version[visible_tile_rear_list.get(tile_rear_id)] = ver;
					}
				}
				}
			}
			
			pre_title_download_thrp = tile_download_thrp ;// check throughput: if throughput decreases --> update tiles' versions
	    	downloadTile(tile_id, tile_version[tile_id]);

	    	// current time
	    	t_now = seg_download_time;
	    	
	    	// tinh thoi gian tai tile (tile_download_time)
	    	tile_size = tile_BR[seg_id][tile_id][tile_version[tile_id]]; // kbits = Bitrate x do dai (1)
	    	sum_tile_BR+=tile_size;
	    	i=0;
	    	while(t_sum > time_trace[i]) i++;
	    	if((time_trace[i] - t_sum) * bandwidth_trace[i] > tile_size) {
	    		tile_download_time = tile_size/bandwidth_trace[i];
	    	}	
	    	else {
	    		tile_download_time = (time_trace[i] - t_sum) + (tile_size - (time_trace[i]-t_sum)*bandwidth_trace[i])/bandwidth_trace[i+1];
	    	}
	    	
	    	// tinh throughput (RC) khi tai tile (tile_download_thrp)
	    	tile_download_thrp = tile_size/tile_download_time;
	    	seg_download_time += tile_download_time; // update tong thoi gian download tile
			t_sum += tile_download_time;
	    	System.out.printf("seg #%d tile #%d thrp:%.4f \n", seg_id+index_1*56, tile_id, tile_download_thrp);
	   	
	    }


	    System.out.printf("tile_version:\n");
	    for(tile_id = 0; tile_id < N_tile; tile_id ++) {
	  		System.out.printf("%d ", tile_version[tile_id]);
	  		if((tile_id + 1)%8 == 0)
	  			System.out.printf("\n");
	      }	
	    // tinh toan chat luong cua VIEWPORT tai frame dau tien
	    double vp_quality = 0; // viewport quality
	    
	    phi = head_trace[seg_id*SEG_LEN][0];
	    theta = head_trace[seg_id * SEG_LEN][1];
	    
	    tile_weight = vmask[phi][theta];
	    
	    for(tile_id = 0; tile_id < N_tile; tile_id ++) {
	    	vp_quality += tile_PSNR[seg_id][tile_id][tile_version[tile_id]] * tile_weight[tile_id]; // weighted sum of tile quality
	    }
	    //System.out.printf("RC: %f, Selected Version: %d, ViewportQuality = %.2f\n", RC, QP_list[version_id-1], vp_quality);
	    
	    	    
	        // update buffer size
	        if(seg_id == 0) {
	        	buffer_size_seg[seg_id] = 1.0;
	        	buffer_time_seg[seg_id] = 0.0;
	        }else {
	        	if(seg_id == 1)
	        		delay_time = 0;
	        	if(seg_download_time + delay_time > buffer_size_seg[seg_id-1])
	        		buffer_time_seg[seg_id] = seg_download_time + delay_time - buffer_size_seg[seg_id-1];
	        	else
	        		buffer_time_seg[seg_id] = 0;
	        	
	        	if(buffer_time_seg[seg_id] > 0)
	        		buffer_size_seg[seg_id] = 1.0;
	        	else
	        		buffer_size_seg[seg_id] = buffer_size_seg[seg_id-1] + (1.0 - seg_download_time - delay_time);
	        }
			// calculate buffering time of segment i
//			if(seg_download_time > 1)
//		    	buffer_time_seg[seg_id]=seg_download_time-1;
//		    else
//		    	buffer_time_seg[seg_id]=0;
		    // calculate throughput
			double seg_down_thrp = sum_tile_BR/seg_download_time;
			double network_thrp = 0;
			switch(THRP_ADAPT_METHOD) {
				case 0:
					network_thrp = seg_down_thrp;               // average segment throughput
					break;
				case 1:
					network_thrp = tile_download_thrp;          // last tile throuhput
					break;
			}
		    // update RC
		    RC = network_thrp * thres;
		    System.out.printf("----seg_down_time=%.4f buffering=%.4f RC=%.4f\n", seg_download_time, buffer_time_seg[seg_id],RC);
		    log_file.printf("%.4f, %d, %.4f, %.4f, %.4f, %.4f, %.4f, %.4f\n", t_sum, seg_id+index_1*56, seg_down_thrp, RC, buffer_size_seg[seg_id], seg_download_time, buffer_time_seg[seg_id], vp_quality);	  
	    
		    // wait until when the next segment become available
		    if(t_sum < (seg_id+1) * 1.0) {
		    	delay_time = ( (seg_id + 1) * 1.0) - t_sum;
		    	t_sum = (seg_id + 1) * 1.0;
		    }else {
		    	delay_time = 0;
		    }
		        
    }	
    double buffer_time = 0;
    for(int seg_id = 0; seg_id < 56; seg_id++) {
		//System.out.println(buffer_time_seg[seg_id]);
		buffer_time+=buffer_time_seg[seg_id];
	}
    //System.out.println("buffer_time: "+ buffer_time);
    buffer_time_1 += buffer_time ;
    N_seg_2 = N_seg_2 -56;
    }
	System.out.println("buffer_time: "+ buffer_time_1);
    log_file.close();
    socket.close();
  }
  
  
  public static void downloadTile(int tile_id, int tile_version) throws IOException, InterruptedException {
	   
	   String tile_id_str;
	   if(tile_id < 9)
  		tile_id_str = "0" + (tile_id+1);
  	   else
  		tile_id_str = "" + (tile_id+1);
	   
	    
	    out.println("GET /360video/QP_" + QP_list[tile_version] + "/tile" + tile_id_str + ".dat HTTP/1.1");
	    out.println("Host: 192.168.1.100:80");
	    out.println("Connection: Open");
	    out.println();
	    
	    FileOutputStream outFile= new FileOutputStream("Tile/tile" + tile_id_str + ".dat");
	    byte[] buffer =new byte[1800];
	    int count = in.read(buffer);
	    
	    
	    String s = new String(buffer);
	    int a = s.indexOf("\r\n\r\n");
	    int b = a + 4;
	    
//	    System.out.printf(s);
	    int start = s.indexOf("Content-Length: ");
	    int end = s.substring(start).indexOf("\r\n") + start;
	    long content_len = Long.parseLong(s.substring(start+16, end));
	    
	    /* write image data to file */
	    long write_byte = 0;
	    
	    outFile.write(buffer,b,count-(a+4));
	    write_byte += (count-(a+4));
	    
	    while(write_byte < content_len){
	        count = in.read(buffer);
	        outFile.write(buffer, 0, count);
	        write_byte += count;
	    }
	    outFile.flush();
	    outFile.close();
	    
	  
  }
  
}

