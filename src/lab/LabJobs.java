package lab;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapreduce.*;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/** Three genuine Hadoop MapReduce jobs. All examples use one reducer. */
public final class LabJobs extends Configured implements Tool {
    public static class DedupMapper extends Mapper<LongWritable, Text, Text, NullWritable> {
        protected void map(LongWritable offset, Text line, Context context)
                throws IOException, InterruptedException {
            String s = line.toString().trim();
            if (s.isEmpty()) return;
            String[] fields = s.split("\\s+");
            if (fields.length != 2) throw new IOException("Expected date and value: " + s);
            context.write(new Text(fields[0] + "\t" + fields[1]), NullWritable.get());
        }
    }
    public static class DedupReducer extends Reducer<Text, NullWritable, Text, NullWritable> {
        protected void reduce(Text key, Iterable<NullWritable> values, Context context)
                throws IOException, InterruptedException {
            context.write(key, NullWritable.get());
        }
    }
    public static class SortMapper extends Mapper<LongWritable, Text, IntWritable, NullWritable> {
        protected void map(LongWritable offset, Text line, Context context)
                throws IOException, InterruptedException {
            String s = line.toString().trim();
            if (s.isEmpty()) return;
            try {
                context.write(new IntWritable(Integer.parseInt(s)), NullWritable.get());
            } catch (NumberFormatException e) {
                throw new IOException("Invalid 32-bit integer: " + s, e);
            }
        }
    }
    public static class SortReducer extends Reducer<IntWritable, NullWritable, LongWritable, IntWritable> {
        private long rank = 0;
        protected void reduce(IntWritable number, Iterable<NullWritable> values, Context context)
                throws IOException, InterruptedException {
            // Iterate values so repeated integers retain their own sequential positions.
            for (NullWritable ignored : values) context.write(new LongWritable(++rank), number);
        }
    }
    public static class FamilyMapper extends Mapper<LongWritable, Text, Text, Text> {
        protected void map(LongWritable offset, Text line, Context context)
                throws IOException, InterruptedException {
            String s = line.toString().trim();
            if (s.isEmpty()) return;
            String[] pair = s.split("\\s+");
            if (pair.length == 2 && pair[0].equalsIgnoreCase("child")
                    && pair[1].equalsIgnoreCase("parent")) return;
            if (pair.length != 2) throw new IOException("Expected child and parent: " + s);
            context.write(new Text(pair[1]), new Text("C\t" + pair[0]));
            context.write(new Text(pair[0]), new Text("P\t" + pair[1]));
        }
    }
    public static class FamilyReducer extends Reducer<Text, Text, Text, Text> {
        protected void setup(Context context) throws IOException, InterruptedException {
            context.write(new Text("grandchild"), new Text("grandparent"));
        }
        protected void reduce(Text middle, Iterable<Text> values, Context context)
                throws IOException, InterruptedException {
            Set<String> children = new TreeSet<>();
            Set<String> parents = new TreeSet<>();
            for (Text value : values) {
                String[] tagged = value.toString().split("\t", 2);
                if (tagged[0].equals("C")) children.add(tagged[1]);
                else parents.add(tagged[1]);
            }
            for (String child : children)
                for (String parent : parents)
                    context.write(new Text(child), new Text(parent));
        }
    }
    public int run(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: LabJobs <dedup|sort|family> <input-directory> <output-directory>");
            return 2;
        }
        Job job = Job.getInstance(getConf(), "lab1-" + args[0]);
        job.setJarByClass(LabJobs.class);
        job.setNumReduceTasks(1); // Numeric rank is global; one output header and one result file.
        switch (args[0]) {
            case "dedup":
                job.setMapperClass(DedupMapper.class);
                job.setCombinerClass(DedupReducer.class);
                job.setReducerClass(DedupReducer.class);
                job.setMapOutputKeyClass(Text.class);
                job.setMapOutputValueClass(NullWritable.class);
                job.setOutputKeyClass(Text.class);
                job.setOutputValueClass(NullWritable.class);
                break;
            case "sort":
                job.setMapperClass(SortMapper.class);
                job.setReducerClass(SortReducer.class);
                job.setMapOutputKeyClass(IntWritable.class);
                job.setMapOutputValueClass(NullWritable.class);
                job.setOutputKeyClass(LongWritable.class);
                job.setOutputValueClass(IntWritable.class);
                break;
            case "family":
                job.setMapperClass(FamilyMapper.class);
                job.setReducerClass(FamilyReducer.class);
                job.setMapOutputKeyClass(Text.class);
                job.setMapOutputValueClass(Text.class);
                job.setOutputKeyClass(Text.class);
                job.setOutputValueClass(Text.class);
                break;
            default: throw new IllegalArgumentException("Unknown job " + args[0]);
        }
        FileInputFormat.addInputPath(job, new Path(args[1]));
        FileOutputFormat.setOutputPath(job, new Path(args[2]));
        return job.waitForCompletion(true) ? 0 : 1;
    }
    public static void main(String[] args) throws Exception {
        System.exit(ToolRunner.run(new LabJobs(), args));
    }
}
