/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */
package org.apis.net.swarm;

import org.apis.net.swarm.DPA;
import org.apis.net.swarm.Key;
import org.apis.net.swarm.SectionReader;
import org.apis.net.swarm.Util;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Created by Admin on 11.06.2015.
 */
public class SimpleDPA extends DPA {
    Random rnd = new Random(0);
    Map<Key, SectionReader> store = new HashMap<>();

    public SimpleDPA() {
        super(null);
    }

    @Override
    public SectionReader retrieve(Key key) {
        return store.get(key);
    }

    @Override
    public Key store(SectionReader reader) {
        byte[] bb = new byte[16];
        rnd.nextBytes(bb);
        Key key = new Key(bb);
        store.put(key, reader);
        return key;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SimpleDPA:\n");
        for (Map.Entry<Key, SectionReader> entry : store.entrySet()) {
            sb.append("  ").append(entry.getKey()).append(": ")
                    .append(Util.readerToString(entry.getValue())).append('\n');
        }
        return sb.toString();
    }


}
