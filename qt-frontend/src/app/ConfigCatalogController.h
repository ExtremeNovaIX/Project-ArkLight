#pragma once

#include <QObject>
#include <QSet>
#include <QVariantList>
#include <QVariantMap>

class ConfigCatalogController final : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool loading READ loading NOTIFY busyChanged)
    Q_PROPERTY(bool saving READ saving NOTIFY busyChanged)
    Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY messageChanged)
    Q_PROPERTY(QString actionMessage READ actionMessage NOTIFY messageChanged)
    Q_PROPERTY(QString responseStatus READ responseStatus NOTIFY messageChanged)
    Q_PROPERTY(QString lastFetchedAt READ lastFetchedAt NOTIFY messageChanged)
    Q_PROPERTY(QString configDir READ configDir NOTIFY catalogChanged)
    Q_PROPERTY(bool hasPendingChanges READ hasPendingChanges NOTIFY valuesChanged)
    Q_PROPERTY(int valuesRevision READ valuesRevision NOTIFY valuesChanged)
    Q_PROPERTY(QString activeFileName READ activeFileName WRITE setActiveFileName NOTIFY catalogChanged)
    Q_PROPERTY(QVariantList configPages READ configPages NOTIFY catalogChanged)
    Q_PROPERTY(QVariantMap activePage READ activePage NOTIFY catalogChanged)

public:
    explicit ConfigCatalogController(QObject *parent = nullptr);

    bool loading() const;
    bool saving() const;
    QString errorMessage() const;
    QString actionMessage() const;
    QString responseStatus() const;
    QString lastFetchedAt() const;
    QString configDir() const;
    bool hasPendingChanges() const;
    int valuesRevision() const;
    QString activeFileName() const;
    QVariantList configPages() const;
    QVariantMap activePage() const;

    Q_INVOKABLE void fetchConfigs();
    Q_INVOKABLE void saveCurrentConfig();
    Q_INVOKABLE void saveChangedConfigs();
    Q_INVOKABLE QVariant fieldValue(const QString &fileName, const QString &fieldKey) const;
    Q_INVOKABLE void setFieldValue(const QString &fileName, const QString &fieldKey, const QVariant &value);
    Q_INVOKABLE QStringList historyFor(const QString &fileName, const QString &fieldKey) const;
    Q_INVOKABLE void applyHistory(const QString &fileName, const QString &fieldKey, const QString &value);
    Q_INVOKABLE bool fieldSupportsHistory(const QVariantMap &field) const;

public slots:
    void setActiveFileName(const QString &value);

signals:
    void catalogChanged();
    void valuesChanged();
    void busyChanged();
    void messageChanged();

private:
    QString fieldStorageKey(const QString &fileName, const QString &fieldKey) const;
    QVariant fallbackFieldValue(const QVariantMap &field) const;
    QVariantMap pageByFileName(const QString &fileName) const;
    QVariantMap currentPage() const;
    bool pageHasChanges(const QString &fileName) const;
    bool saveConfigFile(const QString &fileName, QString *savedTitle, QString *error);
    void refreshDirtyState(const QString &fileName);
    void setValuesFromPages();
    void rememberHistory(const QVariantMap &page);
    void loadHistories();
    void saveHistories() const;
    void clearMessages();
    void setLoading(bool value);
    void setSaving(bool value);
    void setErrorMessage(const QString &value);
    void setActionMessage(const QString &value);
    void setResponseStatus(const QString &value);
    void stampFetchedAt();

    QVariantList m_configPages;
    QVariantMap m_fieldValues;
    QVariantMap m_fieldHistories;
    QSet<QString> m_dirtyFileNames;
    QString m_configDir;
    QString m_activeFileName;
    QString m_errorMessage;
    QString m_actionMessage;
    QString m_responseStatus;
    QString m_lastFetchedAt;
    bool m_loading = false;
    bool m_saving = false;
    int m_valuesRevision = 0;
};
