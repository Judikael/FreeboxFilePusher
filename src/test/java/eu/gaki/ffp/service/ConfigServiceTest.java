package eu.gaki.ffp.service;

import java.io.IOException;
import java.nio.file.Paths;

import org.junit.Assert;
import org.junit.Test;

public class ConfigServiceTest {
    
    @Test
    public void createConfigService() throws IOException {
        
        ConfigService cs = new ConfigService(Paths.get("eu/gaki/fft/freeboxFilePusher.properties"));
        
        Assert.assertEquals("www-data/rss.xml", cs.getRssLocation());
        
    }
}