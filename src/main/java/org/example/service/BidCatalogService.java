package org.example.service;

import org.example.model.catalog.dto.CatalogResult;
import org.example.repo.BidCatalogRepository;
import org.example.repo.TenderRequirementsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BidCatalogService {

    private final BidCatalogRepository bidCatalogRepository;
    private final TenderRequirementsRepository requirementRepository;
    private final CatalogValidator catalogValidator;

    public BidCatalogService(BidCatalogRepository bidCatalogRepository,
                             TenderRequirementsRepository requirementRepository,
                             CatalogValidator catalogValidator) {
        this.bidCatalogRepository = bidCatalogRepository;
        this.requirementRepository = requirementRepository;
        this.catalogValidator = catalogValidator;
    }

    /**
     * 保存投标目录：先校验目录数据，再落库；一个招标要求只保留一份目录，
     * 重复保存覆盖旧目录，返回目录批次主键。
     */
    @Transactional
    public Long save(Long tenderRequirementId, CatalogResult result) {
        catalogValidator.validate(result);
        Long enterpriseId = requirementRepository.findEnterpriseId(tenderRequirementId);
        if (enterpriseId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "招标要求不存在：" + tenderRequirementId);
        }
        return bidCatalogRepository.replace(tenderRequirementId, enterpriseId, result);
    }
}
