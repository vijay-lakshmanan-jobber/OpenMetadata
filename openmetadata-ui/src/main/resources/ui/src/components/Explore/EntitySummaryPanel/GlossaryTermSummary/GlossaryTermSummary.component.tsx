/*
 *  Copyright 2022 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import { Col, Divider, Row, Space, Typography } from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { SummaryEntityType } from '../../../../enums/EntitySummary.enum';
import { GlossaryTerm } from '../../../../generated/entity/data/glossaryTerm';
import { getGlossaryTermByFQN } from '../../../../rest/glossaryAPI';
import { getFormattedEntityData } from '../../../../utils/EntitySummaryPanelUtils';
import { OwnerLabel } from '../../../common/OwnerLabel/OwnerLabel.component';
import SummaryPanelSkeleton from '../../../Skeleton/SummaryPanelSkeleton/SummaryPanelSkeleton.component';
import TagButton from '../../../TagButton/TagButton.component';
import SummaryList from '../SummaryList/SummaryList.component';
import { BasicEntityInfo } from '../SummaryList/SummaryList.interface';
import { GlossaryTermSummaryProps } from './GlossaryTermSummary.interface';

function GlossaryTermSummary({
  entityDetails,
  isLoading,
}: GlossaryTermSummaryProps) {
  const { t } = useTranslation();
  const [selectedData, setSelectedData] = useState<GlossaryTerm>();

  const formattedColumnsData: BasicEntityInfo[] = useMemo(() => {
    if (selectedData?.children) {
      return getFormattedEntityData(
        SummaryEntityType.COLUMN,
        selectedData.children
      );
    } else {
      return [];
    }
  }, [selectedData]);

  const reviewers = useMemo(
    () => entityDetails.reviewers ?? [],
    [selectedData]
  );

  const synonyms = useMemo(() => entityDetails.synonyms ?? [], [selectedData]);

  const fetchGlossaryTermDetails = useCallback(async () => {
    try {
      const response = await getGlossaryTermByFQN(
        entityDetails.fullyQualifiedName,
        'relatedTerms,reviewers,tags,owner,children'
      );
      setSelectedData(response);
    } catch (error) {
      // Error
    }
  }, [entityDetails.fullyQualifiedName, setSelectedData]);

  useEffect(() => {
    fetchGlossaryTermDetails();
  }, [entityDetails]);

  return (
    <SummaryPanelSkeleton loading={Boolean(isLoading)}>
      <>
        <Row className="m-md m-t-0" gutter={[0, 8]}>
          <Col span={24}>
            <Typography.Text
              className="summary-panel-section-title"
              data-testid="reviewer-header">
              {t('label.reviewer-plural')}
            </Typography.Text>
          </Col>
          <Col span={24}>
            {reviewers.length > 0 ? (
              <Space wrap size={[8, 8]}>
                {reviewers.map((assignee) => (
                  <OwnerLabel
                    key={assignee.fullyQualifiedName}
                    owner={assignee}
                  />
                ))}
              </Space>
            ) : (
              <Typography.Text
                className="text-grey-body"
                data-testid="no-reviewer-header">
                {t('label.no-reviewer')}
              </Typography.Text>
            )}
          </Col>
        </Row>

        <Divider className="m-y-xs" />

        <Row className="m-md" gutter={[0, 8]}>
          <Col span={24}>
            <Typography.Text
              className="summary-panel-section-title"
              data-testid="synonyms-header">
              {t('label.synonym-plural')}
            </Typography.Text>
          </Col>
          <Col span={24}>
            {synonyms.length > 0 ? (
              <div className="d-flex flex-wrap">
                {synonyms.map((synonym) => (
                  <TagButton
                    className="glossary-synonym-tag"
                    key={synonym}
                    label={synonym}
                  />
                ))}
              </div>
            ) : (
              <Typography.Text
                className="text-grey-body"
                data-testid="no-synonyms-available-header">
                {t('message.no-synonyms-available')}
              </Typography.Text>
            )}
          </Col>
        </Row>

        <Divider className="m-y-xs" />

        <Row className="m-md" gutter={[0, 8]}>
          <Col span={24}>
            <Typography.Text
              className="summary-panel-section-title"
              data-testid="children-header">
              {t('label.children')}
            </Typography.Text>
          </Col>
          <Col span={24}>
            <SummaryList
              emptyPlaceholderText={t('label.no-entity', {
                entity: t('label.children-lowercase'),
              })}
              entityType={SummaryEntityType.COLUMN}
              formattedEntityData={formattedColumnsData}
            />
          </Col>
        </Row>
      </>
    </SummaryPanelSkeleton>
  );
}

export default GlossaryTermSummary;
