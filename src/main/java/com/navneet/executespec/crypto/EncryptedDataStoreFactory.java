package com.navneet.executespec.crypto;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Set;

import com.google.api.client.util.IOUtils;
import com.google.api.client.util.store.AbstractDataStore;
import com.google.api.client.util.store.AbstractDataStoreFactory;
import com.google.api.client.util.store.DataStore;
import com.google.api.client.util.store.DataStoreFactory;

/**
 * 2026-07-17 credential gate — encrypting wrapper around any Google-client {@link
 * DataStoreFactory}. Values (e.g. Gmail {@code StoredCredential} refresh tokens) are serialized,
 * enveloped through {@link SimpleKeyEncryptionService}, and only then handed to the delegate store
 * — so the on-disk token file carries {@code enc:v1:} envelopes instead of plaintext OAuth
 * material. In passthrough mode (crypto disabled, dev/local) values round-trip unchanged.
 */
public final class EncryptedDataStoreFactory extends AbstractDataStoreFactory {

  private final DataStoreFactory delegate;
  private final SimpleKeyEncryptionService crypto;

  public EncryptedDataStoreFactory(DataStoreFactory delegate, SimpleKeyEncryptionService crypto) {
    this.delegate = delegate;
    this.crypto = crypto;
  }

  @Override
  protected <V extends Serializable> DataStore<V> createDataStore(String id) throws IOException {
    DataStore<String> raw = delegate.getDataStore(id);
    return new EncryptedDataStore<>(this, id, raw, crypto);
  }

  private static final class EncryptedDataStore<V extends Serializable>
      extends AbstractDataStore<V> {

    private final DataStore<String> delegate;
    private final SimpleKeyEncryptionService crypto;

    private EncryptedDataStore(
        EncryptedDataStoreFactory factory,
        String id,
        DataStore<String> delegate,
        SimpleKeyEncryptionService crypto) {
      super(factory, id);
      this.delegate = delegate;
      this.crypto = crypto;
    }

    @Override
    public Set<String> keySet() throws IOException {
      return delegate.keySet();
    }

    @Override
    public Collection<V> values() throws IOException {
      Collection<V> values = new ArrayList<>();
      for (String key : delegate.keySet()) {
        V value = get(key);
        if (value != null) values.add(value);
      }
      return values;
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(String key) throws IOException {
      // Generics are erased on the delegate: a pre-gate store returns the raw deserialized
      // credential object, a post-gate store returns the envelope String. Handle both so legacy
      // token files keep working until their next write re-envelopes them.
      Object stored = ((DataStore<Serializable>) (DataStore<?>) delegate).get(key);
      if (stored == null) return null;
      if (stored instanceof String envelope) {
        byte[] serialized = Base64.getDecoder().decode(crypto.decrypt(envelope));
        return IOUtils.deserialize(serialized);
      }
      return (V) stored;
    }

    @Override
    public DataStore<V> set(String key, V value) throws IOException {
      byte[] serialized = IOUtils.serialize(value);
      delegate.set(key, crypto.encrypt(Base64.getEncoder().encodeToString(serialized)));
      return this;
    }

    @Override
    public DataStore<V> clear() throws IOException {
      delegate.clear();
      return this;
    }

    @Override
    public DataStore<V> delete(String key) throws IOException {
      delegate.delete(key);
      return this;
    }
  }
}
