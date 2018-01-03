/**
 * Copyright (c) 2015-2017, Winter Lau (javayou@gmail.com).
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.oschina.j2cache.redis;

import net.oschina.j2cache.Cache;
import net.oschina.j2cache.util.SerializationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.BinaryJedisCommands;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;

/**
 * Redis 缓存操作封装，基于 Hashs 实现多个 Region 的缓存（
 *
 * TODO: 日后的版本需要支持多种模式
 *
 * @author wendal
 * @author Winter Lau(javayou@gmail.com)
 */
public class RedisCache implements Cache {

    private final static Logger log = LoggerFactory.getLogger(RedisCache.class);

    private String namespace;
    private String region;
    private byte[] regionBytes;
    private RedisClient client;

    /**
     * 缓存构造
     * @param namespace 命名空间，用于在多个实例中避免 key 的重叠
     * @param region 缓存区域的名称
     * @param client 缓存客户端接口
     */
    public RedisCache(String namespace, String region, RedisClient client) {
        if (region == null || region.isEmpty())
            region = "_"; // 缺省region

        this.client = client;
        this.namespace = namespace;
        this.region = getRegionName(region);
        this.regionBytes = region.getBytes();
    }

    /**
     * 在region里增加一个可选的层级,作为命名空间,使结构更加清晰
     * 同时满足小型应用,多个J2Cache共享一个redis database的场景
     *
     * @param region
     * @return
     */
    private String getRegionName(String region) {
        if (namespace != null && !namespace.isEmpty()) {
            region = namespace + ":" + region;
        }
        return region;
    }

    private byte[] getKeyName(Object key) {
        if (key instanceof Number)
            return ("I:" + key).getBytes();
        else if (key instanceof String || key instanceof StringBuilder || key instanceof StringBuffer)
            return ("S:" + key).getBytes();
        return ("O:" + key).getBytes();
    }

    @Override
    public Serializable get(String key) throws IOException {
        if (null == key)
            return null;
        byte[] bytes = client.get().hget(regionBytes, getKeyName(key));
        return SerializationUtils.deserialize(bytes);
    }

    @Override
    public void put(String key, Serializable value) throws IOException {
        if (key == null)
            return;
        if (value == null)
            evict(key);
        else
            client.get().hset(regionBytes, getKeyName(key), SerializationUtils.serialize(value));
    }

    @Override
    public Map getAll(Collection<String> keys) throws IOException {
        BinaryJedisCommands cmd = client.get();
        Map<Serializable, Serializable> values = new HashMap<>();
        for(Serializable key : keys) {
            byte[] bytes = client.get().hget(regionBytes, getKeyName(key));
            values.put(key, SerializationUtils.deserialize(bytes));
        }
        return values;
    }

    @Override
    public boolean exists(String key) {
        return client.get().hexists(regionBytes, getKeyName(key));
    }

    @Override
    public Serializable putIfAbsent(String key, Serializable value) throws IOException {
        byte[] keyBytes = getKeyName(key);
        BinaryJedisCommands cmd = client.get();
        if(!cmd.hexists(regionBytes, keyBytes)) {
            cmd.hset(regionBytes, keyBytes, SerializationUtils.serialize(value));
            return null;
        }
        return SerializationUtils.deserialize(cmd.hget(regionBytes, keyBytes));
    }

    @Override
    public void putAll(Map<String, Serializable> elements) throws IOException {
        BinaryJedisCommands cmd = client.get();
        elements.forEach((k,v) -> {
            try {
                cmd.hset(regionBytes, getKeyName(k), SerializationUtils.serialize(v));
            } catch (IOException e) {
                log.error("Failed putAll", e);
            }
        });
    }

    @Override
    public void update(String key, Serializable value) throws IOException {
        this.put(key, value);
    }

    @Override
    public void evict(String...keys) {
        if (keys == null || keys.length == 0)
            return;
        byte[][] o_keys = new byte[keys.length][];
        for (int i = 0; i < keys.length; i++) {
            o_keys[i] = getKeyName(keys[i]);
        }
        client.get().hdel(regionBytes, o_keys);
    }

    @Override
    public Collection<String> keys() {
        List<String> keys = new ArrayList<>();
        client.get().hkeys(regionBytes).forEach(keyBytes -> {
            try {
                keys.add((String)SerializationUtils.deserialize(keyBytes));
            }catch(IOException e){}
        });
        return keys;
    }

    @Override
    public void clear() {
        client.get().del(regionBytes);
    }

}
