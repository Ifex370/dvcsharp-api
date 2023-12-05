package com.teamapt.moneytor.fcmb.products.modules.groinvestments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fcmb.mobileapp.coreapis.apis.transfers.IntrabankTransferService;
import com.fcmb.mobileapp.coreapis.apis.transfers.models.*;
import com.fcmb.mobileapp.coreapis.models.MoneytorApiResponse;
import com.fcmb.mobileapp.coreapis.utils.BaseMicroserviceResponse;
import com.fcmb.mobileapp.coreapis.utils.MicroserviceSecurityUil;
import com.teamapt.aptent.commons.lib.model.AptentResponseCodes;
import com.teamapt.aptent.integration.service.AptentTransferService;
import com.teamapt.aptent.notification.lib.NotificationServiceResponse;
import com.teamapt.aptent.transfer.lib.request.AccountsTransferRequest;
import com.teamapt.aptent.transfer.lib.request.TransferServiceRequest;
import com.teamapt.aptent.transfer.lib.request.TransferServiceRequestType;
import com.teamapt.email.model.EmailNotification;
import com.teamapt.exceptions.CosmosServiceException;
import com.teamapt.moneytor.fcmb.products.common.clients.GroApiClient;
import com.teamapt.moneytor.fcmb.products.modules.groinvestments.apimodel.*;
import com.teamapt.moneytor.fcmb.products.modules.groinvestments.enums.Currency;
import com.teamapt.moneytor.fcmb.products.modules.groinvestments.enums.InvestmentStatus;
import com.teamapt.moneytor.fcmb.products.modules.groinvestments.enums.Product;
import com.teamapt.moneytor.fcmb.products.modules.groinvestments.enums.RollOverMethod;
import com.teamapt.moneytor.fcmb.products.modules.groinvestments.enums.TopUpPolicy;
import com.teamapt.moneytor.fcmb.products.modules.groinvestments.models.TopUpRequest;
import com.teamapt.moneytor.fcmb.products.modules.groinvestments.models.TransactionHistory;
import com.teamapt.moneytor.fcmb.products.utils.FCMBEmailService;
import com.teamapt.moneytor.frontoffice.lib.account.service.MoneytorAccountService;
import com.teamapt.moneytor.frontoffice.lib.audit.MoneytorAuditService;
import com.teamapt.moneytor.frontoffice.lib.common.constants.ErrorMessages;
import com.teamapt.moneytor.frontoffice.lib.common.util.MoneytorUtils;
import com.teamapt.moneytor.frontoffice.lib.integration.service.MoneytorNotificationService;
import com.teamapt.moneytor.lib.common.customer.model.CustomerAccount;
import com.teamapt.moneytor.lib.common.customer.model.User;
//import com.teamapt.moneytor.lib.cpm.model.CpmInfo;
//import com.teamapt.moneytor.lib.cpm.model.repository.CpmInfoRepository;
import com.teamapt.moneytor.lib.makepayment.common.service.FcmbMoneytorNotificationService;
import com.teamapt.moneytor.lib.makepayment.common.service.FcmbTransactionLimitService;
import com.teamapt.moneytor.lib.makepayment.common.service.UserRequestDetailsChecker;
import com.teamapt.moneytor.lib.makepayment.root.enums.AuditActions;
import com.teamapt.moneytor.lib.makepayment.root.service.FcmbMoneytorUserService;
import com.teamapt.moneytor.lib.makepayment.root.service.FcmbValidationService;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.security.Principal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class GroProductService {


    Logger logger = LoggerFactory.getLogger(GroProductService.class);

    @Autowired
    FcmbValidationService validationService;

    @Autowired
    MoneytorAccountService accountService;

    @Autowired
    MoneytorAuditService auditService;

    @Autowired
    FCMBEmailService notificationService;

    @Autowired
    private AptentTransferService transferService;

    @Autowired
    private IntrabankTransferService intrabankTransferService;

    @Autowired
    FcmbMoneytorUserService userService;

    @Autowired
    MoneytorNotificationService moneytorNotificationService;

    @Autowired
    FcmbTransactionLimitService limitService;

    @Autowired
    UserRequestDetailsChecker detailsChecker;

    @Autowired
    FcmbMoneytorNotificationService fcmbMoneytorNotificationService;

    @Autowired
    GroApiClient groApiClient;

    @Autowired
    @Qualifier("transferRestTemplate")
    private RestTemplate transferRestTemplate;
    @Autowired
    private MicroserviceSecurityUil microserviceSecurityUil;

    @Autowired
    private ObjectMapper objectMapper;

//    @Autowired
//    CpmInfoRepository cpmInfoRepository;

    @Value("${fcmb.settlement.account}")
    private String settlementAccount;

    @Value("${fcmb.settlement.account.name}")
    private String settlementAccountName;

    private static final int[] dollarProducts = new int[2];
    @Value("${fcmb.settlement.account.dollar:3504172294}")
    private String settlementAccountDollar;

    @Value("${audit.domain}")
    private String auditDomain;

    @Value("${gro.interest.defined.start.time:23:50}")
    private String definedStartTime;

    @Qualifier("dateTimeObjectMapper")

    @Value("${intrabank.base.url:}")
    private String baseUrl;
    @Value("${intrabank.transfer.with.fee:}")
    private String transferWithFeeUrl;

    @Value("${fcmb.settlement.account.name.dollar:FCMB MICRO-LENDING (GRO DOLLAR IMPLEMENTATION- USD ACCOUNT)}")
    private String settlementAccountNameDollar;

    @PostConstruct
    private void constructDollarList() {
        dollarProducts[0] = Product.GroFlexDollar.getValue();
        dollarProducts[1] = Product.GroMaxDollar.getValue();
    }

    public Object getProducts() throws CosmosServiceException {
        return groApiClient.getMethod("/products");
    }

    public Object createInvestment(Principal principal, CreateGroInvestment request) throws CosmosServiceException {
        boolean isValueGiven = false;

        String transId = MoneytorUtils.generateBatchKey("GRTRN");
        if (Arrays.stream(dollarProducts).anyMatch(x -> x == request.getProductId()))
            transId = MoneytorUtils.generateBatchKey("GR") + "GRD";
        String narration = "", remark = "";
        IntraBankTransferResponse response = new IntraBankTransferResponse();
        try {
            logger.info("In here");
            User user = userService.getUser(principal);
//            String clientId = detailsChecker.getClientId();

            CustomerAccount debitAccount = accountService.getAccount(user, request.getDebitAccount());
            CustomerAccount creditAccount = accountService.getAccount(user, request.getCreditAccount());
            logger.info("Credit Account " + creditAccount.toString());
            Optional<Product> pro = Product.valueOf(request.getProductId());
            if (pro.isPresent()) {
                remark = getProductShortName(pro.get().name()) + "/invst/" + transId;
                narration = getProductShortName(pro.get().name()) + " invst/" + transId;

            } else
                throw new CosmosServiceException("Invalid product selected");

            if (StringUtils.isEmpty(request.getPin())) {
                throw new CosmosServiceException("Transaction Pin is required");
            }

            validationService.validatePin(principal, debitAccount.getCbaCustomerId(), request.getPin());
            logger.info("Validation passed");
            GroInvestmentRequest apiRequest = new GroInvestmentRequest().withCreateRequest(request);
            apiRequest.setCreditAccount(creditAccount.getAccountNumber());
            apiRequest.setDebitAccount(debitAccount.getAccountNumber());
            apiRequest.setFullname(debitAccount.getAccountName());
            apiRequest.setEmail(debitAccount.getEmail());
            apiRequest.setPhoneNumber(debitAccount.getPhoneNumber() != null ? debitAccount.getPhoneNumber() : "");
            apiRequest.setIdentifier(validationService.getBvn(debitAccount.getCbaCustomerId()));

            //debit customer on type of gro investment
//            response = doDebit(debitAccount,user,request,transId,narration,remark);

            MoneytorApiResponse<IntraBankTransferResponse> transferApiResponse = doDebit(debitAccount, user, request, transId, narration, remark);

            if(transferApiResponse.getResponseCode().equals(AptentResponseCodes.COMPLETED)) {
                response = transferApiResponse.getData();

                logger.info("Customer was successfully debited");
                auditService.save(user, AuditActions.SINGLE_TRANSFER_SERVICE.name(), "Debit successful");
                Object o = groApiClient.postMethod("/investment", apiRequest);
//                Object o =  groApiClient.postMethod("/investment/5",apiRequest);
                if (o == null) {
                    isValueGiven = false;
                } else {
                    isValueGiven = true;
                    auditService.save(user, AuditActions.GRO_INVESTMENT_SERVICE.name(), "Investment Created");

                    try {
                        InvestmentResponse ir = objectMapper.readValue(objectMapper.writeValueAsString(o), InvestmentResponse.class);
                        CreateInvestmentNotification investmentNotification = new CreateInvestmentNotification();
                        investmentNotification.setAccountNumber(apiRequest.getDebitAccount());
                        investmentNotification.setAmount(apiRequest.getAmount().doubleValue());
                        investmentNotification.setCurrency(Currency.valueOf(pro.get().name().contains("Dollar") ? "USD" : "NGN"));
                        investmentNotification.setCustomerEmail(apiRequest.getEmail());
                        investmentNotification.setCustomerName(apiRequest.getFullname());
                        investmentNotification.setRate(ir.getRate());
                        investmentNotification.setTenor(apiRequest.getTenor());

                        double accruedInterest = this.computeInterest(ir.getPrincipal().doubleValue(), ir.getRate(), ir.getTenor());
                        investmentNotification.setInterestAmount(accruedInterest);
                        investmentNotification.setMaturityAmount(ir.getPrincipal().add(new BigDecimal(accruedInterest)).doubleValue());
                        investmentNotification.setStartDate(ir.getStartDate());
                        investmentNotification.setMaturityDate(ir.getMaturityDate());
                        investmentNotification.setType(ir.getProductName());

                        EmailNotification emailNotification = new EmailNotification();
                        emailNotification.setSubject("Gro Investment Receipt");
//                    emailNotification.setRecipients(new ArrayList<>(Collections.singleton("ayotola.jinadu@fcmb.com")));
                        emailNotification.setRecipients(new ArrayList<>(Collections.singleton(investmentNotification.getCustomerEmail())));
                        emailNotification.setBody(createEmailBody(investmentNotification));
                        emailNotification.setSender("Gro Investment Service");
                        moneytorNotificationService.sendEmail(emailNotification);
                    } catch (Exception ex) {
                        ex.printStackTrace();
//                        throw new CosmosServiceException();
                    }
                }
                return o;
            } else {
                throw new CosmosServiceException("Unable to Debit customer");
            }
        } catch (CosmosServiceException e) {
            auditService.save(principal, request, response, AuditActions.SINGLE_TRANSFER_SERVICE.name(), auditDomain);
            logger.error("An exception occurred", e);
            if (!isValueGiven) {
                //doReversal
                if (response != null && response.getCustomerReference() != null && response.getStan() != null) {
                    try {
                        TransferReversalRequest transferReversalRequest = new TransferReversalRequest();
                        transferReversalRequest.setOriginalCustomerReference(response.getCustomerReference());
                        transferReversalRequest.setStan(response.getStan());
                        MoneytorApiResponse<Object> reversal = intrabankTransferService.reversal(transferReversalRequest);
                        if (!AptentResponseCodes.COMPLETED.equals(reversal.getResponseCode()))
                            throw new CosmosServiceException(reversal.getNarration());
                        auditService.save(principal, AuditActions.GRO_INVESTMENT_SERVICE.name(), "Reversal successful");
                        logger.info("reversal successful");
                    } catch (Exception ex) {
                        logger.error("reversal failed: " + ex.getMessage());
                        auditService.save(principal, AuditActions.GRO_INVESTMENT_SERVICE.name(), "Reversal failed");
                        throw new CosmosServiceException("Sorry Gro investment creation failed");
                    }
                }
            }
            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CosmosServiceException("Failed to create investment");
        }
    }

//    public Object getInvestments(Principal principal)throws CosmosServiceException{
//        CustomerAccount account = accountService.getDefaultAccount(userService.getUser(principal));
//        String bvn = validationService.getBvn(account.getCbaCustomerId());
//        Object investmentResponse = groApiClient.getMethod("/investments/"+bvn);
//        try {
//            List<InvestmentResponse> investmentResponseList = objectMapper.readValue(objectMapper.writeValueAsString(investmentResponse), new TypeReference<List<InvestmentResponse>>(){});
//            performInterestOperation(investmentResponseList);
//        }catch (IOException jpe) {
//            jpe.printStackTrace();
//            logger.info("Could not parse investment response and perform interest operation");
//        }
//        return investmentResponse;
//    }

    public List<InvestmentResponseResult> getInvestments(Principal principal) throws CosmosServiceException {
        CustomerAccount account = accountService.getDefaultAccount(userService.getUser(principal));
        String bvn = validationService.getBvn(account.getCbaCustomerId());
        List<InvestmentResponse> investmentResponseList = groApiClient.getInvestmentListForCustomer(bvn);

        LinkedList<InvestmentResponseResult> investmentResponseResultList = new LinkedList<>();// Added this for backward compatibility with old android versions
        investmentResponseList.forEach(irl -> {
            InvestmentResponseResult investmentResponseResult;
            if (irl.getTopUpRequest() == null) {
                irl.setComputedPrincipal(irl.getPrincipal().setScale(2, RoundingMode.DOWN));
                irl.setComputedInterest(irl.getAccruedInterest().setScale(2, RoundingMode.DOWN));
                investmentResponseResult = new InvestmentResponseResult(irl);
            } else {
                irl.setComputedPrincipal(
                        irl.getPrincipal()
                                .add(BigDecimal.valueOf(irl.getTopUpRequest().getTopUpAmountInvested()))
                                .setScale(2, RoundingMode.DOWN)
                );
                irl.setComputedInterest(
                        irl.getAccruedInterest()
                                .add(BigDecimal.valueOf(irl.getTopUpRequest().getCalculatedInterest()))
                                .setScale(2, RoundingMode.DOWN)
                );
                investmentResponseResult = new InvestmentResponseResult(irl);
                investmentResponseResult.setTopUpRequest(new TopUpResponseRequest(irl.getTopUpRequest()));
            }
            investmentResponseResultList.add(investmentResponseResult);
        });

        try {
            performInterestOperation(investmentResponseList);
        } catch (Exception jpe) {
            jpe.printStackTrace();
            logger.info("Could not parse investment response and perform interest operation");
        }

        return investmentResponseResultList;
    }

    public Object calculateInterest(CalculateInterestRequest request) throws CosmosServiceException {
        return groApiClient.postMethod("/investment/calculate", request);
    }

    public LiquidationPreviewResponse calculateLiquidation(Long investmentId) throws CosmosServiceException {
        //return groApiClient.getMethod("/calculate-liquidation/" + investmentId);
        return groApiClient.getLiquidationPreviewResponse("/calculate-liquidation/" + investmentId);
    }

    private RequeryResponse performRequery(RequeryRequest requeryRequest) {
        try {
            MoneytorApiResponse<RequeryResponse> requeryResponse = intrabankTransferService.getTransactionStatusAsOnFinacle(requeryRequest);
            if (requeryResponse != null && requeryResponse.getResponseCode().equals(AptentResponseCodes.COMPLETED)) {
                RequeryResponse responseData = requeryResponse.getData();
                String failureMessage = "Requery failed - " + requeryResponse.getNarration();
                if (responseData == null) throw new CosmosServiceException(failureMessage);
                RequeryResponseData data = responseData.getResponse();
                if (data == null) throw new CosmosServiceException(failureMessage);
                if (!requeryRequest.getReference().equals(data.getRemarks())) {
                    throw new CosmosServiceException("Requery failed - Remarks do not match");
                }
                return requeryResponse.getData();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    private MoneytorApiResponse<IntraBankTransferResponse> doDebit(CustomerAccount debitAccount, User user, CreateGroInvestment request, String transId, String narration, String remark) throws CosmosServiceException {
        TranHistoryRequest tranHistoryRequest = new TranHistoryRequest(transId);
        boolean isDebit = false;
//        if (Arrays.stream(dollarProducts).anyMatch(x -> x == request.getProductId())) {
//            settlementAccount = settlementAccountDollar;
//            settlementAccountName = settlementAccountNameDollar;
//        }
        boolean isDollarProduct = Arrays.stream(dollarProducts).anyMatch(x -> x == request.getProductId());
        try {
//            TransferServiceResponse response = transferService.processSingleOneTimeTransferHasUnniqueOptn("214",debitAccount.getAccountNumber(),debitAccount.getAccountName(),"214",
//                    "First City Monument Bank",settlementAccount,settlementAccountName,"Settle Account",transId,request.getMinorAmount(),narration,transId,clientId,
//                    request.getPin(),"NGN",debitAccount.getCbaCustomerId(), UserType.BUSINESS.equals(user.getUserType()) ? "SME":"");
            TransferWithFeeRequest transferWithFeeRequest = new TransferWithFeeRequest();
            transferWithFeeRequest.setAmount(BigDecimal.valueOf(request.getMinorAmount()).divide(BigDecimal.valueOf(100)));
            transferWithFeeRequest.setCreditAccountNo(isDollarProduct ? this.settlementAccountDollar : this.settlementAccount);
//                    transferWithFeeRequest.setCreditAccountNo(request.getAccountId());
            transferWithFeeRequest.setCurrency(isDollarProduct ? "USD" : "NGN");
            transferWithFeeRequest.setCustomerReference(transId);
            transferWithFeeRequest.setDebitAccountNo(debitAccount.getAccountNumber());
            transferWithFeeRequest.setFees(false);
            transferWithFeeRequest.setNarration((narration != null && narration.length() > 45) ? narration.substring(0, 45) : narration);
            transferWithFeeRequest.setRemark((remark != null && remark.length() > 30) ? remark.substring(0, 30) : remark);
            transferWithFeeRequest.setCharges(new ArrayList<>());

            MoneytorApiResponse<IntraBankTransferResponse> response = intrabankTransferService.doTransferWithMerchantFee(transferWithFeeRequest);

            if (response != null && response.getResponseCode().equals(AptentResponseCodes.COMPLETED)) {
                // Completed...
                try {
                    tranHistoryRequest.setTranStatus(InvestmentStatus.COMPLETE.name());
                    tranHistoryRequest.setAmountPaid(transferWithFeeRequest.getAmount().doubleValue());
                    groApiClient.saveTransactionHistory(tranHistoryRequest);
                } catch (CosmosServiceException cse) {
                    logger.info("Failed to save transaction history - " + cse.getMessage());
                    cse.printStackTrace();
                }
                return response;
            } else {
                // Perform re-query...

                RequeryResponse requeryResponse = performRequery(new RequeryRequest(tranHistoryRequest.getGeneratedTranRef(), debitAccount.getAccountNumber()));

                String tranStatus = InvestmentStatus.FAILED.name();
                String failReason = response != null ?
                        (response.getResponseCode().equals(AptentResponseCodes.FAILED) ? response.getNarration() : "")
                        : "";
                if (requeryResponse != null) {
                    tranStatus = InvestmentStatus.COMPLETE.name();
                    tranHistoryRequest.setAmountPaid(transferWithFeeRequest.getAmount().doubleValue());
                    logger.info("Operation successful - CPM failed but operation was posted");
                }

                try {
                    tranHistoryRequest.setTranStatus(tranStatus);
                    tranHistoryRequest.setFailReason(failReason);
                    groApiClient.saveTransactionHistory(tranHistoryRequest);
                } catch (CosmosServiceException cse) {
                    logger.info("Failed to save transaction history - " + cse.getMessage());
                    cse.printStackTrace();
                }

                if (tranStatus.equals(InvestmentStatus.FAILED.name())) {
                    throw new CosmosServiceException("Unable to debit customer.");
                }

                return new MoneytorApiResponse<>(AptentResponseCodes.COMPLETED, "Confirmed customer debit");
            }
        } catch (Exception e) {
            logger.warn(e.getMessage());
            logger.warn("Exception in debiting customer " + e.getMessage());
            throw new CosmosServiceException(ErrorMessages.UNABLE_TO_COMPLETE_REQUEST);
        }
//       return new IntraBankTransferResponse();
    }

    private TransferServiceRequest doReversal(CreateGroInvestment request, CustomerAccount customerAccount, String narration, String transId) {
        String curr = "NGN";
        String accountUsable = settlementAccount;
        String accountUsableName = settlementAccountName;
        if (Arrays.stream(dollarProducts).anyMatch(x -> x == request.getProductId())) {
            accountUsable = settlementAccountDollar;
            accountUsableName = settlementAccountNameDollar;
            curr = "USD";
        }

        TransferServiceRequest transferServiceRequest = new TransferServiceRequest();
        transferServiceRequest.setTransactionPin(request.getPin());
        transferServiceRequest.setBankId("214");
        transferServiceRequest.setTransferServiceRequestType("DIRECT_BULK_TRANSFER");
        transferServiceRequest.setUserType(com.teamapt.aptent.user.management.lib.model.UserType.ONLINE_BANK_USER);
        transferServiceRequest.setClientId("3MNT0001");
        transferServiceRequest.setTransferServiceRequestType(TransferServiceRequestType.TRANSFER_REVERSAL);
        transferServiceRequest.setReversalRequest(true);

        AccountsTransferRequest transferRequest = new AccountsTransferRequest();
        transferRequest.setAmount(BigDecimal.valueOf(request.getMinorAmount()).divide(BigDecimal.valueOf(100d)));
        transferRequest.setTransactionRef(transId);
        transferRequest.setCurrencyCode(curr);
        transferRequest.setCustomerId(customerAccount.getCbaCustomerId());
        transferRequest.setNarration(narration);
        transferRequest.setFromAccountBankCode("214");
        transferRequest.setTrackingRequestId(transId);
        transferRequest.setToAccountBankCode("214");
        transferRequest.setFromAccount(accountUsable);
        transferRequest.setFromAccountName(accountUsableName);
        transferRequest.setToAccount(customerAccount.getAccountNumber());
        transferRequest.setToAccountName(customerAccount.getAccountName());
        transferServiceRequest.setAccountsTransferRequest(transferRequest);

        return transferServiceRequest;
    }

    public List<TopUpRequest> getTopRequest(TopUpPolicy topUpPolicy) throws CosmosServiceException {

        try {

            return groApiClient.getMethodWithParam("/get-top-up-requests", topUpPolicy);
        } catch (CosmosServiceException e) {

            e.printStackTrace();
            throw new CosmosServiceException(e.getMessage());
        }

    }

    public List<TopUpRequest> getListOfTopUp() throws CosmosServiceException {
        List<TopUpRequest> list = new ArrayList<>();
        try {
            list = groApiClient.getTopUp("/get-top-up-list");

        } catch (CosmosServiceException e) {
            e.printStackTrace();
            throw new CosmosServiceException(e.getMessage());
        }

        return list;
    }

    public void getTopUpListByInvestmentId(Long investmentId) {
    }

    private List<InvestmentResponse> getInvestmentsByTenorLeft(Integer tenorLeft) throws CosmosServiceException {
        try {
            return groApiClient.getInvestMentResponse("/investment/by-tenor-left", tenorLeft);
        } catch (CosmosServiceException e) {
            logger.info("Exception gotten " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public void scheduleNotification() throws CosmosServiceException {
        if (!CollectionUtils.isEmpty(getInvestmentsByTenorLeft(2))) {
            getInvestmentsByTenorLeft(2).forEach(
                    i -> {
                        logger.info("Sending mails......");
                        //send
                        String subject = "Your Gro Investment: " + i.getInvestmentName() + " Will Soon Mature";
                        EmailNotification emailNotification = new EmailNotification();
                        emailNotification.setSubject(subject);
                        emailNotification.setRecipients(new ArrayList<>(Collections.singleton("olumide.ogundele@fcmb.com")));
                        emailNotification.setBody(emailBody(i));
                        emailNotification.setSender("Gro Investment Service");
                        try {
                            /**
                             * Send Email notification
                             * */
                            moneytorNotificationService.sendEmail(Arrays.asList(i.getGroCustomer().getEmail()), null, null, emailBody(i), subject);
                        } catch (CosmosServiceException e) {
                            e.printStackTrace();
                        }
                    }
            );
        } else {
            logger.info("No request");
        }

    }

    public String createEmailBody(CreateInvestmentNotification investmentResponse) {

        return "<!DOCTYPE html>\n" +
                "<html xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:o=\"urn:schemas-microsoft-com:office:office\" lang=\"en\"><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\">\n" +
                "  \n" +
                "  <meta http-equiv=\"x-ua-compatible\" content=\"ie=edge\">\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
                "  <meta name=\"x-apple-disable-message-reformatting\">\n" +
                "  <title>Credit Card Statement Review</title>\n" +
                "\n" +
                "  <style type=\"text/css\">\n" +
                "\n" +
                "    @import url('https://fonts.googleapis.com/css?family=Lato:300,400,700|Open+Sans');\n" +
                "    @media only screen {\n" +
                "      .col, td, th, div, p {font-family: \"Corbel\",-apple-system,system-ui,BlinkMacSystemFont,\"Segoe UI\",\"Roboto\",\"Helvetica Neue\",Arial,sans-serif;}\n" +
                "      .webfont {font-family: \"Corbel\",-apple-system,system-ui,BlinkMacSystemFont,\"Segoe UI\",\"Roboto\",\"Helvetica Neue\",Arial,sans-serif;}\n" +
                "    }\n" +
                "\n" +
                "    a {text-decoration: none;}\n" +
                "    img {border: 0; line-height: 100%; max-width: 100%; vertical-align: middle;}\n" +
                "    #outlook a, .links-inherit-color a {padding: 0; color: inherit;}\n" +
                "    .col {font-size: 13px; line-height: 22px; vertical-align: top;}\n" +
                "\n" +
                "    .hover-scale:hover {transform: scale(1.2);}\n" +
                "    .video {display: block; height: auto; object-fit: cover;}\n" +
                "    .star:hover a, .star:hover ~ .star a {color: #FFCF0F!important;}\n" +
                "\n" +
                "    @media only screen and (max-width: 600px) {\n" +
                "      .video {width: 100%;}\n" +
                "      u ~ div .wrapper {min-width: 100vw;}\n" +
                "      .container {width: 100%!important; -webkit-text-size-adjust: 100%;}\n" +
                "    }\n" +
                "\n" +
                "    @media only screen and (max-width: 480px) {\n" +
                "      .col {\n" +
                "        box-sizing: border-box;\n" +
                "        display: inline-block!important;\n" +
                "        line-height: 20px;\n" +
                "        width: 100%!important;\n" +
                "      }\n" +
                "      .col-sm-1 {max-width: 25%;}\n" +
                "      .col-sm-2 {max-width: 50%;}\n" +
                "      .col-sm-3 {max-width: 75%;}\n" +
                "      .col-sm-third {max-width: 33.33333%;}\n" +
                "      .col-sm-auto {width: auto!important;}\n" +
                "      .col-sm-push-1 {margin-left: 25%;}\n" +
                "      .col-sm-push-2 {margin-left: 50%;}\n" +
                "      .col-sm-push-3 {margin-left: 75%;}\n" +
                "      .col-sm-push-third {margin-left: 33.33333%;}\n" +
                "\n" +
                "      .full-width-sm {display: table!important; width: 100%!important;}\n" +
                "      .stack-sm-first {display: table-header-group!important;}\n" +
                "      .stack-sm-last {display: table-footer-group!important;}\n" +
                "      .stack-sm-top {display: table-caption!important; max-width: 100%; padding-left: 0!important;}\n" +
                "\n" +
                "      .toggle-content {\n" +
                "        max-height: 0;\n" +
                "        overflow: auto;\n" +
                "        transition: max-height .4s linear;\n" +
                "        -webkit-transition: max-height .4s linear;\n" +
                "      }\n" +
                "      .toggle-trigger:hover + .toggle-content,\n" +
                "      .toggle-content:hover {max-height: 999px!important;}\n" +
                "\n" +
                "      .show-sm {\n" +
                "        display: inherit!important;\n" +
                "        font-size: inherit!important;\n" +
                "        line-height: inherit!important;\n" +
                "        max-height: none!important;\n" +
                "      }\n" +
                "      .hide-sm {display: none!important;}\n" +
                "\n" +
                "      .align-sm-center {\n" +
                "        display: table!important;\n" +
                "        float: none;\n" +
                "        margin-left: auto!important;\n" +
                "        margin-right: auto!important;\n" +
                "      }\n" +
                "      .align-sm-left {float: left;}\n" +
                "      .align-sm-right {float: right;}\n" +
                "\n" +
                "      .text-sm-center {text-align: center!important;}\n" +
                "      .text-sm-left {text-align: left!important;}\n" +
                "      .text-sm-right {text-align: right!important;}\n" +
                "\n" +
                "      .nav-sm-vertical .nav-item {display: block!important;}\n" +
                "      .nav-sm-vertical .nav-item a {display: inline-block; padding: 5px 0!important;}\n" +
                "\n" +
                "      .h1 {font-size: 32px !important;}\n" +
                "      .h2 {font-size: 24px !important;}\n" +
                "      .h3 {font-size: 16px !important;}\n" +
                "\n" +
                "      .borderless-sm {border: none!important;}\n" +
                "      .height-sm-auto {height: auto!important;}\n" +
                "      .line-height-sm-0 {line-height: 0!important;}\n" +
                "      .overlay-sm-bg {background: #232323; background: rgba(0,0,0,0.4);}\n" +
                "\n" +
                "      u ~ div .wrapper .toggle-trigger {display: none!important;}\n" +
                "      u ~ div .wrapper .toggle-content {max-height: none;}\n" +
                "      u ~ div .wrapper .nav-item {display: inline-block!important; padding: 0 10px!important;}\n" +
                "      u ~ div .wrapper .nav-sm-vertical .nav-item {display: block!important;}\n" +
                "\n" +
                "      .p-sm-0 {padding: 0!important;}\n" +
                "      .p-sm-8 {padding: 8px!important;}\n" +
                "      .p-sm-16 {padding: 16px!important;}\n" +
                "      .p-sm-24 {padding: 24px!important;}\n" +
                "      .pt-sm-0 {padding-top: 0!important;}\n" +
                "      .pt-sm-8 {padding-top: 8px!important;}\n" +
                "      .pt-sm-16 {padding-top: 16px!important;}\n" +
                "      .pt-sm-24 {padding-top: 24px!important;}\n" +
                "      .pr-sm-0 {padding-right: 0!important;}\n" +
                "      .pr-sm-8 {padding-right: 8px!important;}\n" +
                "      .pr-sm-16 {padding-right: 16px!important;}\n" +
                "      .pr-sm-24 {padding-right: 24px!important;}\n" +
                "      .pb-sm-0 {padding-bottom: 0!important;}\n" +
                "      .pb-sm-8 {padding-bottom: 8px!important;}\n" +
                "      .pb-sm-16 {padding-bottom: 16px!important;}\n" +
                "      .pb-sm-24 {padding-bottom: 24px!important;}\n" +
                "      .pl-sm-0 {padding-left: 0!important;}\n" +
                "      .pl-sm-8 {padding-left: 8px!important;}\n" +
                "      .pl-sm-16 {padding-left: 16px!important;}\n" +
                "      .pl-sm-24 {padding-left: 24px!important;}\n" +
                "      .px-sm-0 {padding-right: 0!important; padding-left: 0!important;}\n" +
                "      .px-sm-8 {padding-right: 8px!important; padding-left: 8px!important;}\n" +
                "      .px-sm-16 {padding-right: 16px!important; padding-left: 16px!important;}\n" +
                "      .px-sm-24 {padding-right: 24px!important; padding-left: 24px!important;}\n" +
                "      .py-sm-0 {padding-top: 0!important; padding-bottom: 0!important;}\n" +
                "      .py-sm-8 {padding-top: 8px!important; padding-bottom: 8px!important;}\n" +
                "      .py-sm-16 {\n" +
                "    padding-top: 3px!important;\n" +
                "    padding-bottom: 3px!important;\n" +
                "}\n" +
                "      .py-sm-24 {padding-top: 24px!important; padding-bottom: 24px!important;}\n" +
                "    }\n" +
                "  body,td,th {\n" +
                "    font-family: \"Corbel\", -apple-system, system-ui, BlinkMacSystemFont, \"Segoe UI\", Roboto, \"Helvetica Neue\", Arial, sans-serif;\n" +
                "}\n" +
                "  a:link {\n" +
                "    color: #5E2785;\n" +
                "    text-decoration: none;\n" +
                "}\n" +
                "  .px-sm-8 {\n" +
                "}\n" +
                "th{\n" +
                "  text-align: left;\n" +
                "}\n" +
                "  </style>\n" +
                "\n" +
                "</head>\n" +
                "<body style=\"box-sizing:border-box;margin:0;padding:0;width:100%;word-break:break-word;-webkit-font-smoothing:antialiased;\" link=\"#5E2785\">\n" +
                "\n" +
                "<div style=\"display:none;font-size:0;line-height:0;\"><!-- Add your inbox preview text here --></div>\n" +
                "\n" +
                "<table class=\"wrapper\" role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "  <tbody><tr>\n" +
                "    <td class=\"px-sm-16\" bgcolor=\"#EEEEEE\" align=\"center\">\n" +
                "      <table class=\"container\" role=\"presentation\" width=\"640\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "        <tbody><tr>\n" +
                "          <td class=\"px-sm-8\" style=\"padding: 0 24px;\" align=\"left\">\n" +
                "            <div class=\"spacer\" style=\"line-height: 8px;\"> </div>\n" +
                "            <div class=\"spacer\" style=\"line-height: 8px;\"> </div>\n" +
                "          </td>\n" +
                "        </tr>\n" +
                "      </tbody></table>\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "</tbody></table>\n" +
                "<table class=\"wrapper\" role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "  <tbody><tr>\n" +
                "    <td class=\"px-sm-16\" bgcolor=\"#EEEEEE\" align=\"center\">\n" +
                "      <table class=\"container\" role=\"presentation\" width=\"640\" cellspacing=\"0\" cellpadding=\"0\" align=\"center\">\n" +
                "        <tbody><tr>\n" +
                "          <td class=\"px-sm-8\" style=\"padding: 10px 24px;\" ali=\"\" gn=\"left\" width=\"638\" bgcolor=\"#FFFFFF\">\n" +
                "\n" +
                "                        <table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "              <tbody><tr>\n" +
                "                <td class=\"col\" style=\"padding: 0 8px;\" width=\"127\">\n" +
                "                  <p style=\"color: #888888; font-size: 12px; margin: 0;\"><img src=\"https://purplestor.blob.core.windows.net/appnotification/gro/FCMB-logo_for-DM.gif\" alt=\"Gro Investment Notification\"></p>\n" +
                "                </td>\n" +
                "              </tr>\n" +
                "            </tbody></table>\n" +
                "            <div class=\"spacer line-height-sm-0\" style=\"line-height: 10px;\"><br>\n" +
                "            </div>\n" +
                "            <table role=\"presentation\" width=\"100%\" height=\"350\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "              <tbody><tr>\n" +
                "<td style=\"padding: 0 10px;\" height=\"350px\" align=\"center\"><h2 class=\"webfont h1\" style=\"color: #FFFFFF; font-size: 52px; font-weight: 400; line-height: 100%; margin: 0;\"><span class=\"fullCenter Corbel\" style=\"color: #5E2785; font-family: Corbel; font-weight: 700; vertical-align: top; font-size: 30px; text-align: left; line-height: 40px; text-transform: none;\"><img src=\"https://purplestor.blob.core.windows.net/appnotification/gro/SocialMedia_GRO.jpg\" alt=\"GRO Investment Notification\" style=\"border-radius: 10px;\" width=\"640\"></span>\n" +
                "              </h2></td>\n" +
                "              </tr>\n" +
                "            </tbody></table>\n" +
                "\n" +
                "            <table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" align=\"center\">\n" +
                "              <tbody><tr>\n" +
                "                <td class=\"col pb-sm-16\" style=\"padding: 0 8px;\">\n" +
                "                <p><span class=\"webfont\" style=\"color: #5E2785; font-size: 18px; font-weight: 700; margin: 0;\">Dear " + investmentResponse.getCustomerName() + ",</span></p>\n" +
                "\n" +
                "                  <p>Your GRO investment has been successfully set up!  Here are the details:</p>\n" +
                "\n" +
                "                    <table width=\"60%\" cellspacing=\"0\" cellpadding=\"3\" border=\"1\">\n" +
                "                    <tbody>\n" +
                "                      <tr>\n" +
                "                        <td wid=\"\" th=\"300\"><strong>Customer Name:</strong></td>\n" +
                "                        <td wi=\"\" dth=\"300\">" + investmentResponse.getCustomerName() + "</td>\n" +
                "                      </tr>\n" +
                "\n" +
                "                      <tr>\n" +
                "                        <td wid=\"\" th=\"300\"><strong>Account Number:</strong></td>\n" +
                "                        <td wid=\"\" th=\"300\">" + investmentResponse.getAccountNumber() + "</td>\n" +
                "                      </tr>\n" +
                "                    </tbody>\n" +
                "                  </table>\n" +
                "                  <p></p>\n" +
                "                  <table width=\"100%\" cellspacing=\"0\" cellpadding=\"3\" border=\"1\">\n" +
                "                    <tbody>\n" +
                "                      <tr>\n" +
                "                        <td wid=\"\" th=\"300\"><strong>Investment Type: </strong></td>\n" +
                "                        <td wi=\"\" dth=\"300\">" + investmentResponse.getType() + "</td>\n" +
                "                      </tr>\n" +
                "\n" +
                "                      <tr>\n" +
                "                        <td wid=\"\" th=\"300\"><strong>Investment Amount:</strong></td>\n" +
//                "                        <td wid=\"\" th=\"300\">"+ investmentResponse.getCurrency() + " " + investmentResponse.getAmount() + "</td>\n" +
                "                        <td wid=\"\" th=\"300\">" + investmentResponse.getCurrency() + " " + (investmentResponse.getAmount() > 0 ? BigDecimal.valueOf(investmentResponse.getAmount()).setScale(2, RoundingMode.HALF_EVEN) : "0.0") + "</td>\n" +
                "                      </tr>\n" +
                "\n" +
                "                      <tr>\n" +
                "                        <td wid=\"\" th=\"300\"><strong>Investment Date:</strong></td>\n" +
                "                        <td wid=\"\" th=\"300\">" + (investmentResponse.getStartDate() == null ? "N/A" : investmentResponse.getStartDate().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy"))) + "</td>\n" +
                "                      </tr>\n" +
                "\n" +
                "                      <tr>\n" +
                "                        <td wid=\"\" th=\"300\"><strong>Maturity Date: </strong></td>\n" +
                "                        <td wi=\"\" dth=\"300\">" + (investmentResponse.getMaturityDate() == null ? "N/A" : investmentResponse.getMaturityDate().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy"))) + "</td>\n" +
                "                      </tr>\n" +
                "\n" +
                "                      <tr>\n" +
                "                        <td wid=\"\" th=\"300\"><strong>Tenor:</strong></td>\n" +
                "                        <td wi=\"\" dth=\"300\">" + investmentResponse.getTenor() + " days" + "</td>\n" +
                "                      </tr>\n" +
                "\n" +
                "                      <tr>\n" +
                "                        <td wid=\"\" th=\"300\"><strong>Rate:</strong></td>\n" +
                "                        <td wi=\"\" dth=\"300\">" + investmentResponse.getRate() + "%" + "</td>\n" +
                "                      </tr>\n" +
                "\n" +
                "                      <tr>\n" +
                "                        <td wid=\"\" th=\"300\"><strong>Net Interest: </strong></td>\n" +
                "                        <td wi=\"\" dth=\"300\">" + investmentResponse.getCurrency() + " " + (investmentResponse.getInterestAmount() > 0 ? BigDecimal.valueOf(investmentResponse.getInterestAmount()).setScale(2, RoundingMode.DOWN) : "0.0") + "</td>\n" +
                "                      </tr>\n" +
                "\n" +
                "                      <tr>\n" +
                "                        <td wid=\"\" th=\"300\"><strong>Maturity Amount:</strong></td>\n" +
                "                        <td wi=\"\" dth=\"300\">" + investmentResponse.getCurrency() + " " + (investmentResponse.getMaturityAmount() > 0 ? BigDecimal.valueOf(investmentResponse.getMaturityAmount()).setScale(2, RoundingMode.HALF_EVEN) : "0.0") + "</td>\n" +
                "                      </tr>\n" +
                "\n" +
                "\n" +
                "                    </tbody>\n" +
                "                  </table>\n" +
                "                  \n" +
                "                  <p>Please note that your investment and accrued interest will be liquidated into originating investment account upon maturity.</p>\n" +
                "                 \n" +
                "                  <p>Thank you for choosing FCMB.</p>\n" +
                "                </td>\n" +
                "                </tr>\n" +
                "              <tr>\n" +
                "                <td class=\"col pb-sm-16\" style=\"padding: 0 8px;\">&nbsp;</td>\n" +
                "                </tr>\n" +
                "            </tbody></table>\n" +
                "            <div class=\"spacer line-height-sm-0 py-sm-8\" style=\"line-height: 24px;\"> </div>\n" +
                "          </td>\n" +
                "        </tr>\n" +
                "      </tbody></table>\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "</tbody></table>\n" +
                "<table class=\"wrapper\" role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "  <tbody><tr>\n" +
                "    <td class=\"px-sm-16\" bgcolor=\"#EEEEEE\" align=\"center\">\n" +
                "      <table class=\"container\" role=\"presentation\" width=\"600\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "        <tbody><tr>\n" +
                "          <td>\n" +
                "            <table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "              <tbody><tr>\n" +
                "                <td class=\"col pb-sm-16\" width=\"190\">\n" +
                "                  <table class=\"wrapper\" role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "                    <tbody><tr>\n" +
                "    <td class=\"px-sm-16\" height=\"22\" bgcolor=\"#EEEEEE\" align=\"center\">&nbsp;</td>\n" +
                "  </tr>\n" +
                "</tbody></table>\n" +
                "<table class=\"wrapper\" role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "  <tbody><tr>\n" +
                "    <td class=\"px-sm-16\" bgcolor=\"#EEEEEE\" align=\"center\">\n" +
                "      <table class=\"container\" role=\"presentation\" width=\"640\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "<tbody><tr>\n" +
                "          <td class=\"px-sm-8\" style=\"padding: 0 24px;\" height=\"78\" bgcolor=\"#FFFFFF\" align=\"center\"><span class=\"content-block\" style=\"font-family: Corbel; vertical-align: top; padding-bottom: 10px; padding-top: 10px; font-size: 12px; color: #999999; text-align: center;\"><font color=\"#3a3a3a\">If you have reason to\n" +
                "suspect any unauthorised activity on your account<br style=\"font-family: Corbel;\">\n" +
                "please contact us by\n" +
                "sending an email to </font><font style=\"font-family: Corbel;\" color=\"#ffb81c\">&nbsp;</font><a target=\"_blank\" style=\"color: #5E2186; text-decoration: underline; font-family: Corbel;\" href=\"mailto:frauddesk@fcmb.com\">frauddesk@fcmb.com</a><br>\n" +
                "<br>\n" +
                "<font color=\"#5c068c\"><font color=\"#3a3a3a\">For more information on our products and services, please call our 24/7 <br>contact centre on</font> <font color=\"#5c068c\"> 07003290000</font> or&nbsp;012798640 <font color=\"#3a3a3a\"> or chat with us via <br>Whatsapp on <font color=\"#5c068c\">(+234) 090 999 99814</font> <font color=\"#3a3a3a\"> or</font> <font color=\"#5c068c\"> &nbsp;(+234) 090 999\n" +
                "99815.</font><font color=\"#efefef\"><br>\n" +
                "</font><font color=\"#3a3a3a\">Alternatively send an email to</font> <a target=\"_blank\" style=\"color: rgb(98, 35, 135); text-decoration: underline;\" href=\"mailto:customerservice@fcmb.com\">customerservice@fcmb.com</a><font color=\"#5c068c\"><a target=\"_blank\" style=\"color: rgb(98, 35, 135); text-decoration: underline;\" href=\"mailto:customerservice@fcmb.com\"><img src=\"file:///Users/TISV_Olisa/Documents/TISV_ACCOUNTS_RCV_1017/FCMB/FCMB_PurpleMailers-on-the-go/PurpleMail_F3/download_v.1.0.0/Files/html/templates/marketing/Opportunity%20Network-Email/Dispense%20Error-_files/blank.gif\" width=\"1\" height=\"1\"></a></font>.<br>\n" +
                "<br>\n" +
                "</font></font></span></td>\n" +
                "        </tr>\n" +
                "        <tr>\n" +
                "          <td class=\"px-sm-8\" style=\"padding: 0 24px;\" height=\"79\" bgcolor=\"#FFFFFF\" align=\"center\"><table width=\"100%\" cellspacing=\"5\" cellpadding=\"5\" border=\"0\">\n" +
                "            <tbody>\n" +
                "              <tr>\n" +
                "                <!--<td width=\"381\" align=\"center\" style=\"color: #3A3A3A\"><img src=\"images/my-bank-bar.jpg\" width=\"650\"  alt=\"\" style=\"display:block;\"></td>-->\n" +
                "              </tr>\n" +
                "              <tr>\n" +
                "                <td style=\"color: #3A3A3A\" align=\"center\"><a href=\"https://www.facebook.com/fcmbmybank\"><img src=\"https://purplestor.blob.core.windows.net/appnotification/gro/facebook.png\" alt=\"\" width=\"52\" height=\"52\" border=\"0\"><img src=\"https://purplestor.blob.core.windows.net/appnotification/gro/twitter.png\" alt=\"\" width=\"52\" height=\"52\" border=\"0\"><img src=\"https://purplestor.blob.core.windows.net/appnotification/gro/linkedin.png\" alt=\"\" width=\"52\" height=\"52\" border=\"0\"><img src=\"https://purplestor.blob.core.windows.net/appnotification/gro/instagram.png\" alt=\"\" width=\"52\" height=\"52\" border=\"0\"><img src=\"https://purplestor.blob.core.windows.net/appnotification/gro/whatsapp.png\" alt=\"\" width=\"52\" height=\"52\" border=\"0\"></a></td>\n" +
                "              </tr>\n" +
                "            </tbody>\n" +
                "          </table></td>\n" +
                "        </tr>\n" +
                "      </tbody></table>\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "</tbody></table>\n" +
                "<table class=\"wrapper\" role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "  <tbody><tr>\n" +
                "    <td class=\"px-sm-16\" bgcolor=\"#EEEEEE\" align=\"center\">\n" +
                "      <table class=\"container\" role=\"presentation\" width=\"640\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "        \n" +
                "      </table>\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "</tbody></table>\n" +
                "<table class=\"wrapper\" role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "  <tbody><tr>\n" +
                "    <td class=\"px-sm-16\" bgcolor=\"#EEEEEE\" align=\"center\">\n" +
                "      <table class=\"container\" role=\"presentation\" width=\"640\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "        \n" +
                "      </table>\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "</tbody></table>\n" +
                "<table class=\"wrapper\" role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "  <tbody><tr>\n" +
                "    <td class=\"px-sm-16\" bgcolor=\"#EEEEEE\" align=\"center\">\n" +
                "      <table class=\"container\" role=\"presentation\" width=\"640\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "        <tbody><tr>\n" +
                "          <td bgcolor=\"#FFFFFF\" align=\"left\">&nbsp;</td>\n" +
                "        </tr>\n" +
                "      </tbody></table>\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "</tbody></table>\n" +
                "<table class=\"wrapper\" role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "  <tbody><tr>\n" +
                "    <td class=\"px-sm-16\" bgcolor=\"#EEEEEE\" align=\"center\">\n" +
                "      <table class=\"container\" role=\"presentation\" width=\"640\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "        <tbody><tr>\n" +
                "          <td class=\"px-sm-8\" style=\"padding: 0 24px;\" bgcolor=\"#FFFFFF\" align=\"left\"><table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">\n" +
                "            <tbody><tr>\n" +
                "                <td class=\"col text-sm-center\" style=\"padding: 0 8px;\" width=\"120\" height=\"44\" align=\"right\">\n" +
                "                  <p style=\"color: #888888;\">&nbsp;</p>\n" +
                "                </td>\n" +
                "                <td class=\"col text-sm-center\" style=\"padding: 0 8px;\" width=\"324\" align=\"center\"><span style=\"color: #888888; margin: 0;\"><span class=\"content-block\" style=\"font-family: Corbel; vertical-align: top; padding-bottom: 10px; padding-top: 10px; font-size: 12px; color: #999999; text-align: center;\"><br>\n" +
                "<span style=\"color: #3a3a3a\">Copyright\n" +
                "© 2022. First\n" +
                "City Monument Bank</span></span></span></td>\n" +
                "                <td class=\"col text-sm-center\" style=\"padding: 0 8px;\" width=\"104\" align=\"right\">&nbsp;</td>\n" +
                "              </tr>\n" +
                "          </tbody></table>\n" +
                "            <div class=\"spacer line-height-sm-0 py-sm-8\" style=\"line-height: 48px;\"> </div>\n" +
                "          </td>\n" +
                "        </tr>\n" +
                "      </tbody></table>\n" +
                "    </td>\n" +
                "  </tr>\n" +
                "</tbody></table>\n" +
                "\n" +
                "\n" +
                "</td></tr></tbody></table></td></tr></tbody></table></td></tr></tbody></table></body></html>";
    }

    private String emailBody(InvestmentResponse investmentResponse) {

        return "<html>\n" +
                "    <head></head>\n" +
                "    <body>\n" +
                "        <div>\n" +
                "            <p style=\"color:purple; font-size: 30px; text-align: center; font-weight: bold; text-transform: uppercase;\">Your Gro Investment " + investmentResponse.getInvestmentName() + " Will soon mature</p>\n" +
                "        </div>\n" +
                "        <div style=\"text-align:center;\">\n" +
                "            <img src=\"https://dl.dropboxusercontent.com/s/of0af9n8aksh7sd/customer_week_image_1.png\"  alt=\"\">\n" +
                "        </div>\n" +
                "        <div style=\"text-align: center;\">\n" +
                "            <p style=\"color: purple; font-size: 16px;\">Dear " + investmentResponse.getGroCustomer().getFullname() + "</p>\n" +
                "            <p>Please be informed that your gro investment will mature in <strong>2 days</strong></p>\n" +
                "            <p>Kindly Visit the FCMB mobile app for more details of your investment</p>\n" +
                "            <p>Thank you for choosing FCMB</p>\n" +
                "            <br>\n" +
                "            <br>\n" +
                "            <br>\n" +
                "            <p>Visit <a href=\"www.fcmb.com\">FCMB</a> for more info</p>\n" +
                "        </div>\n" +
                "    </body>\n" +
                "</html>";
    }

    private String emailBody2(InvestmentResponse investmentResponse) {

        return "<html>\n" +
                "    <head></head>\n" +
                "    <body>\n" +
                "        <div>\n" +
                "            <p style=\"color:purple; font-size: 30px; text-align: center; font-weight: bold; text-transform: uppercase;\">Your TOP UP REQUEST HAS BEEN CANCELLED " + "</p>\n" +
                "        </div>\n" +
                "        <div style=\"text-align:center;\">\n" +
                "            <img src=\"https://dl.dropboxusercontent.com/s/of0af9n8aksh7sd/customer_week_image_1.png\"  alt=\"\">\n" +
                "        </div>\n" +
                "        <div style=\"text-align: center;\">\n" +
                "            <p style=\"color: purple; font-size: 16px;\">Dear " + investmentResponse.getGroCustomer().getFullname() + "</p>\n" +
                "            <p>Top-up on " + investmentResponse.getInvestmentName() + " Gro Flex investment has been cancelled due to insufficient funds</p>\n" +
                "            \n" +
                "            <p>Thank you for choosing FCMB</p>\n" +
                "            <br>\n" +
                "            <br>\n" +
                "            <br>\n" +
                "            <p>Visit <a href=\"www.fcmb.com\">FCMB</a> for more info</p>\n" +
                "        </div>\n" +
                "    </body>\n" +
                "</html>";
    }

    public void schedulePostingService() throws CosmosServiceException {
        if (!CollectionUtils.isEmpty(getInvestmentsByTenorLeft(0))) {
            LocalDateTime localDateTime = LocalDateTime.now();
            getInvestmentsByTenorLeft(0).forEach(
                    i -> {
                        //post withdrwal
                        if (localDateTime.isAfter(i.getMaturityDate()) || localDateTime.isEqual(i.getMaturityDate())) {
                            if (i.getRollOver().equals(RollOverMethod.NONE) && !i.getStatus().equals(InvestmentStatus.COMPLETE)) {
                                //pay the whole principal
                                BigDecimal amount = i.getPrincipal();
                                logger.info("Amount " + amount);
                                try {
                                    String transId = MoneytorUtils.generateBatchKey("GR");
                                    String narration = i.getProductName() + "/Principal payment/" + transId;
                                    String remark = "GRO/" + i.getProductName() + "/" + transId;

                                    MoneytorApiResponse<IntraBankTransferResponse> moneytorApiResponse = doTransfer(i, amount, narration, remark, transId);
                                    if (moneytorApiResponse.getResponseCode().equals(AptentResponseCodes.COMPLETED)) {
                                        logger.info("Payment Done");
                                        //credit customer for interest
                                        String transId2 = MoneytorUtils.generateBatchKey("GR");
                                        String narration2 = i.getProductName() + "/Interest payment/" + transId2;
                                        String remark2 = "Gro Interest/" + i.getProductName() + "/" + "/" + transId2;
                                        BigDecimal amount2 = i.getAccruedInterest().setScale(2, RoundingMode.DOWN);
                                        logger.info("" + amount2);

                                        MoneytorApiResponse<IntraBankTransferResponse> response = doTransfer(i, amount2, narration2, remark2, transId2);
                                        if (response.getResponseCode().equals(AptentResponseCodes.COMPLETED)) {
                                            logger.info("Investment " + i);
                                            if ((i.getProductName().equalsIgnoreCase("GroFlex") || i.getProductName().equalsIgnoreCase("GroFlexDollar"))
                                                    && i.getTopUpRequest().getTopUpAmountInvested() > 0) {
                                                String transId3 = MoneytorUtils.generateBatchKey("GR");
                                                String narration3 = i.getProductName() + "/Top-up pymnt/" + transId;
                                                String remark3 = "GRO/" + i.getProductName() + "/" + transId;
                                                BigDecimal amount3 = BigDecimal.valueOf(i.getTopUpRequest().getTopUpAmountInvested());
                                                MoneytorApiResponse<IntraBankTransferResponse> moneytorApiResponse3 = doTransfer(i, amount3, narration3, remark3, transId3);
                                                if (moneytorApiResponse3.getResponseCode().equals(AptentResponseCodes.COMPLETED)) {
                                                    String transId4 = MoneytorUtils.generateBatchKey("GR");
                                                    String narration4 = i.getProductName() + "/Top-up int pymnt/" + transId;
                                                    String remark4 = "GRO/" + i.getProductName() + "/" + transId;
                                                    BigDecimal amount4 = BigDecimal.valueOf(i.getTopUpRequest().getCalculatedInterest()).setScale(2, RoundingMode.DOWN);
                                                    MoneytorApiResponse<IntraBankTransferResponse> moneytorApiResponse4 = doTransfer(i, amount4, narration4, remark4, transId4);
                                                    if (moneytorApiResponse4.getResponseCode().equals(AptentResponseCodes.COMPLETED))
                                                        logger.info(String.format("%s fully paid", i.getProductName()));
                                                }
                                            }
                                            i.setStatus(InvestmentStatus.COMPLETE);
                                            try {
                                                Object o = groApiClient.postMethod("/update-investment", i);
                                                logger.info("Object gotten " + o.toString());
                                            } catch (CosmosServiceException e) {
                                                e.printStackTrace();
                                            }
                                        } else
                                            throw new CosmosServiceException(response.getNarration());

                                    } else
                                        throw new CosmosServiceException(moneytorApiResponse.getNarration());
                                } catch (CosmosServiceException e) {
                                    e.printStackTrace();
                                    logger.info("Investment error " + e.getMessage());
                                }
                            }
                        }
                    }
            );
        } else {
            logger.info("No investment for the time period");
        }
    }

    private MoneytorApiResponse<IntraBankTransferResponse> doTransfer(InvestmentResponse investmentResponse, BigDecimal amount, String narration, String remark, String transId) throws CosmosServiceException {
        try {
            String accountUsable = settlementAccount;
            String curr = "NGN";
            if (Arrays.stream(dollarProducts).anyMatch(x -> x == Product.valueOf(investmentResponse.getProductName().replace(" ", "")).getValue())) {
                accountUsable = settlementAccountDollar;
                curr = "USD";
            }

            TransferWithFeeRequest transferWithFeeRequest = new TransferWithFeeRequest();
            transferWithFeeRequest.setAmount(amount);
            transferWithFeeRequest.setCreditAccountNo(investmentResponse.getCreditAccount());
            transferWithFeeRequest.setCurrency(curr);
            transferWithFeeRequest.setCustomerReference(transId);
            transferWithFeeRequest.setDebitAccountNo(accountUsable);
            transferWithFeeRequest.setFees(false);
            transferWithFeeRequest.setNarration(narration);
            transferWithFeeRequest.setRemark(remark);
            transferWithFeeRequest.setCharges(new ArrayList<>());

            MoneytorApiResponse<IntraBankTransferResponse> moneytorApiResponse = intrabankTransferService.doTransferWithMerchantFee(transferWithFeeRequest);
            if (moneytorApiResponse.getResponseCode() == null || !moneytorApiResponse.getResponseCode().equals(AptentResponseCodes.COMPLETED)) {
                //do audit
                auditService.save(investmentResponse.getCreditAccount(), AuditActions.GRO_INVESTMENT_SERVICE.name(), "Debit failed", auditDomain);
                throw new CosmosServiceException("Debit failed");
            }
            auditService.save(investmentResponse.getCreditAccount(), AuditActions.GRO_INVESTMENT_SERVICE.name(), "Debit successful", auditDomain);
            return moneytorApiResponse;

        } catch (Exception e) {
            throw new CosmosServiceException(e);
        }
    }

    private static final String SEPARATOR = "-";

    @Value("${dollar.currency.code:USD}")
    private String dollarCurrencyCode;

    @Value("${default.currency.code:NGN}")
    private String nairaCurrencyCode;

    private MoneytorApiResponse<IntraBankTransferResponse> doTransfer(Principal principal, InvestmentResponse investmentResponse, BigDecimal amount, String narration, String remark, TranHistoryRequest tranHistoryRequest) throws CosmosServiceException {
        String accountUsable = settlementAccount;
        String curr = nairaCurrencyCode;
        if (Arrays.stream(dollarProducts).anyMatch(x -> x == Product.valueOf(investmentResponse.getProductName().replace(" ", "")).getValue())) {
            accountUsable = settlementAccountDollar;
            curr = dollarCurrencyCode;
        }

        List<TransactionHistory> transactionHistoryList = groApiClient.getTranHistoryRecords(tranHistoryRequest.getOriginalTranRef());
        boolean isProcessed = transactionHistoryList.stream().anyMatch(thr -> thr.getTranStatus().equals(InvestmentStatus.COMPLETE));
        if (isProcessed) {
            logger.info("Transaction has already been processed");
            return new MoneytorApiResponse<>(AptentResponseCodes.COMPLETED, "Operation successful - Transaction previously processed");
        }

        if (!transactionHistoryList.isEmpty()) { // throttle subsequent liquidation attempts
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException ignored) {}
        }

        /* find last attempt and confirm if it has been processed prior */
        Optional<TransactionHistory> lastTransactionHistoryOptional = transactionHistoryList.stream().max((a, b) -> ObjectUtils.compare(a.getCurrentAttempt(), b.getCurrentAttempt()));
        int lastRetry = lastTransactionHistoryOptional.isPresent() ? lastTransactionHistoryOptional.get().getCurrentAttempt() : 0;
        String lastTransRef = tranHistoryRequest.getOriginalTranRef().concat(lastRetry > 0 ? (SEPARATOR + lastRetry) : "");

        if (curr.equals(dollarCurrencyCode)) {
            // fx currencies must end with GRD. // so we trim out the initial
            lastTransRef = tranHistoryRequest.getOriginalTranRef().substring(0, tranHistoryRequest.getOriginalTranRef().length() - 3) + "-" + lastRetry + "GRD";
        }
        logger.info("lastRetry {}, lastTransRef: {}", lastRetry, lastTransRef);

        MoneytorApiResponse<CpmTransactionStatusResponse> statusByRefApiResponse =
                intrabankTransferService.getTransactionStatusByCustomerReference(lastTransRef);

        if (statusByRefApiResponse == null) {
            throw new CosmosServiceException("Temporarily unable to complete this request. Please try again.");
        } else if (statusByRefApiResponse.getResponseCode().equals(AptentResponseCodes.ERROR)) {
            throw new CosmosServiceException("Temporarily unable to complete this request. Please try again");
        } else if (statusByRefApiResponse.getResponseCode().equals(AptentResponseCodes.UNKNOWN)) {
            throw new CosmosServiceException("Unable to complete your request. Please try again.");
        }

        if (statusByRefApiResponse.getResponseCode().equals(AptentResponseCodes.COMPLETED)) {
            final String duplicateErrMsg =
                    String.format("%s attempted a duplicate GRO liquidation of amount %s with transactionReference: " +
                                    "%s to destination account number: %s", investmentResponse.getGroCustomer().getFullname(),
                            amount, lastTransRef, investmentResponse.getCreditAccount());

            auditService.save(investmentResponse.getCreditAccount(), AuditActions.GRO_INVESTMENT_SERVICE.name(), duplicateErrMsg, auditDomain);

            try {
                tranHistoryRequest.setGeneratedTranRef(lastTransRef);
                tranHistoryRequest.setCurrentAttempt(lastRetry);
                tranHistoryRequest.setTranStatus(InvestmentStatus.COMPLETE.name());
                groApiClient.updateTransactionHistory(tranHistoryRequest);
            } catch (CosmosServiceException cse) {
                logger.info("Failed to save transaction history " + cse.getMessage());
                cse.printStackTrace();
            }
            return new MoneytorApiResponse<>(AptentResponseCodes.COMPLETED, "Operation successful - Transaction previously processed");
        }

        try {
            String transRef = tranHistoryRequest.getOriginalTranRef().concat(lastRetry >= 0 ? (SEPARATOR + (lastRetry + 1)) : "");

            if (curr.equals(dollarCurrencyCode)) {
                // fx currencies must end with GRD. // so we adjust the transRef to accommodate it
                transRef = tranHistoryRequest.getOriginalTranRef().substring(0, tranHistoryRequest.getOriginalTranRef().length() - 3) + "-" + (lastRetry + 1) + "GRD";
            }

            tranHistoryRequest.setGeneratedTranRef(transRef);
            tranHistoryRequest.setCurrentAttempt(lastRetry >= 0 ? (lastRetry + 1) : 0);

            logger.info("transHistoryRequest {}", tranHistoryRequest);

            TransferWithFeeRequest transferWithFeeRequest = new TransferWithFeeRequest();
            transferWithFeeRequest.setAmount(amount);
            transferWithFeeRequest.setCreditAccountNo(investmentResponse.getCreditAccount());
            transferWithFeeRequest.setCurrency(curr);
            transferWithFeeRequest.setCustomerReference(tranHistoryRequest.getGeneratedTranRef());
            transferWithFeeRequest.setDebitAccountNo(accountUsable);
            transferWithFeeRequest.setFees(false);
            transferWithFeeRequest.setNarration((narration != null && narration.length() > 45) ? narration.substring(0, 44) : narration);
            transferWithFeeRequest.setRemark((remark != null && remark.length() > 30) ? remark.substring(0, 29) : remark);
            transferWithFeeRequest.setCharges(new ArrayList<>());

            MoneytorApiResponse<IntraBankTransferResponse> moneytorApiResponse = doCpmTransfer(transferWithFeeRequest);

            Map<String, Object> map = objectMapper.convertValue(transferWithFeeRequest, new TypeReference<Map<String, Object>>() {});
            map.put("investmentId", investmentResponse.getId());

            try {
                auditService.save(principal.getName(), map, moneytorApiResponse.getData(), AuditActions.GRO_MANUAL_LIQUIDATION_SERVICE.name(), "CPM debit/credit for GRO investment manual liquidation", auditDomain);
            } catch (Exception ignored) {}

            if (moneytorApiResponse != null && (moneytorApiResponse.getResponseCode().equals(AptentResponseCodes.INCOMPLETE_RESPONSE) || moneytorApiResponse.getNarration().contains("Your transaction may have been processed, Please check your notifications or try again later"))) {
                auditService.save(investmentResponse.getCreditAccount(), AuditActions.GRO_INVESTMENT_SERVICE.name(), "A case of CPM code 94: " + moneytorApiResponse.getNarration() , auditDomain);

                logger.info("A case of CPM code 94: " + moneytorApiResponse.getNarration());
                return new MoneytorApiResponse<>(AptentResponseCodes.COMPLETED, moneytorApiResponse.getNarration());
            }

            if (moneytorApiResponse != null && moneytorApiResponse.getResponseCode().equals(AptentResponseCodes.COMPLETED)) {
                auditService.save(investmentResponse.getCreditAccount(), AuditActions.GRO_INVESTMENT_SERVICE.name(), "Debit successful", auditDomain);
                saveTransactionHistory(tranHistoryRequest, amount.doubleValue(), InvestmentStatus.COMPLETE, null);
                return moneytorApiResponse;
            }

            if (performRequery(new RequeryRequest(tranHistoryRequest.getGeneratedTranRef(), accountUsable)) != null) {
                saveTransactionHistory(tranHistoryRequest, amount.doubleValue(), InvestmentStatus.COMPLETE, null);
                return new MoneytorApiResponse<>(AptentResponseCodes.COMPLETED, "Operation successful - CPM failed but operation was posted");
            }

            saveTransactionHistory(tranHistoryRequest, null, InvestmentStatus.FAILED, moneytorApiResponse != null ? moneytorApiResponse.getNarration() : "");

            auditService.save(investmentResponse.getCreditAccount(), AuditActions.GRO_INVESTMENT_SERVICE.name(), "Debit failed", auditDomain);
            return moneytorApiResponse;

        } catch (Exception e) {
            throw new CosmosServiceException(e);
        }
    }

    private void saveTransactionHistory(TranHistoryRequest tranHistoryRequest, Double amount, InvestmentStatus investmentStatus, String failReason) {
        try {
            tranHistoryRequest.setTranStatus(investmentStatus.name());
            tranHistoryRequest.setAmountPaid(amount);
            tranHistoryRequest.setFailReason(failReason);
            groApiClient.saveTransactionHistory(tranHistoryRequest);
        } catch (CosmosServiceException cse) {
            logger.info("Failed to save transaction history - " + cse.getMessage());
            cse.printStackTrace();
        }
    }

    public MoneytorApiResponse<IntraBankTransferResponse> doCpmTransfer(TransferWithFeeRequest request) {
        String url = this.baseUrl + this.transferWithFeeUrl;
        URI uri = URI.create(url);
        BaseMicroserviceResponse<IntraBankTransferResponse> apiResponse = this.transferRestTemplate.exchange(uri, HttpMethod.POST, new HttpEntity(request, this.getAdditionalSecurityHeaders()), new ParameterizedTypeReference<BaseMicroserviceResponse<IntraBankTransferResponse>>() {
        }).getBody();

        MoneytorApiResponse<IntraBankTransferResponse> response;

        if (apiResponse == null || apiResponse.getCode() == null) {
            response = new MoneytorApiResponse<>(AptentResponseCodes.FAILED, "Error response from service");
        } else if (apiResponse.getCode().equals("00") || apiResponse.getCode().equals("01")) {
            response = new MoneytorApiResponse<>(AptentResponseCodes.COMPLETED, apiResponse.getDescription(), apiResponse.getData());
        } else if (apiResponse.getCode().equals("94")) {
            response = new MoneytorApiResponse<>(AptentResponseCodes.INCOMPLETE_RESPONSE, apiResponse.getDescription(), apiResponse.getData());
        } else {
            response = new MoneytorApiResponse<>(AptentResponseCodes.FAILED,  apiResponse.getDescription());
        }

        return response;
    }

    private HttpHeaders getAdditionalSecurityHeaders() {
        HttpHeaders headers = new HttpHeaders();
        String utcTimestamp = this.microserviceSecurityUil.getCurrentUtcDate(true);
        headers.add("x-token", this.microserviceSecurityUil.getXTokenHeader(utcTimestamp));
        headers.add("UTCTimestamp", this.microserviceSecurityUil.getCurrentUtcDate(false));
        return headers;
    }

    public void processSchedulePayments(List<TopUpRequest> requests) throws CosmosServiceException {
        LocalDateTime localDateTime = LocalDateTime.now();
        if (!CollectionUtils.isEmpty(requests)) {
            for (TopUpRequest request : requests) {
                String curr = "NGN";
                InvestmentResponse i;
                try {
                    i = getInvestmentResponseByInvestmentId(request.getInvestmentId());
                    if (i.getProductName().contains("Dollar")) {
                        curr = "USD";
                    }
                } catch (IOException ex) {
                    logger.error(ex.getMessage(), ex);
                    throw new CosmosServiceException("Unable to find investment");
                }

                String transId = MoneytorUtils.generateBatchKey("GRO");

                String narration = "App: " + i.getProductName().toUpperCase() + "/" + transId + "/TopUp ";
                if ((localDateTime.isAfter(request.getNextPaymentDate())
                        || localDateTime.isEqual(request.getNextPaymentDate()))
                        && request.getMaturityDate().isAfter(localDateTime)
                        && request.getTopUpTimes() > 0) {
                    /**
                     * Try and debit customer
                     * **/
                    TransferWithFeeRequest transferWithFeeRequest = new TransferWithFeeRequest();
                    transferWithFeeRequest.setAmount(BigDecimal.valueOf(request.getTopupAmount()));
                    transferWithFeeRequest.setCreditAccountNo(i.getProductName().contains("Dollar") ? settlementAccountDollar : settlementAccount);
//                    transferWithFeeRequest.setCreditAccountNo(request.getAccountId());
                    transferWithFeeRequest.setCurrency(curr);
                    transferWithFeeRequest.setCustomerReference(transId);
                    transferWithFeeRequest.setDebitAccountNo(request.getAccountId());
                    transferWithFeeRequest.setFees(false);
                    transferWithFeeRequest.setNarration(narration);
                    transferWithFeeRequest.setRemark("GroTopUpRequest" + "/" + transId);
                    transferWithFeeRequest.setCharges(new ArrayList<>());

                    MoneytorApiResponse<IntraBankTransferResponse> moneytorApiResponse = intrabankTransferService.doTransferWithMerchantFee(transferWithFeeRequest);
                    if (moneytorApiResponse.getResponseCode() == null || !moneytorApiResponse.getResponseCode().equals(AptentResponseCodes.COMPLETED)) {
                        try {
                            logger.info("Unable to debit customer");
                            if (moneytorApiResponse.getNarration().equalsIgnoreCase("No Sufficent Funds")) {
                                logger.info("in here2 ");
                                if (request.getFailCount() == 3 || request.getFailCount() > 3) {
                                    logger.info("In here to let fly");
                                    /**
                                     *
                                     *  Also send mail notification stating there was failed debit state
                                     *  Do Audit
                                     *  If fail count is equal to 3, terminate top request
                                     * */

                                    //send mail and sms notifying cutomer that request is cancelled
                                    try {
                                        String subject = "TOP UP REQUEST CANCELLATION";
                                        auditService.save(i.getDebitAccount(), AuditActions.GRO_INVESTMENT_SERVICE.name(), "Topup request cancelled", auditDomain);
                                        NotificationServiceResponse resp = moneytorNotificationService.sendEmail(Collections.singletonList(i.getGroCustomer().getEmail()), null, null, emailBody2(i), subject);

                                        if (resp.getNotificationServiceResponseCode().equals(AptentResponseCodes.COMPLETED)) {
                                            logger.info("Mail sent");
                                            //send sms
                                            String message = "Dear " + i.getGroCustomer().getFullname() + "\n" +
                                                    "Top Up on GRO FLEX Investment has been cancelled due to insufficient funds \n" +
                                                    "Thank you for choosing FCMB";
                                            NotificationServiceResponse response = fcmbMoneytorNotificationService.sendSms(request.getPhoneNumber() != null ? request.getPhoneNumber() : "", message, request.getAccountId());
                                            if (response.getNotificationServiceResponseCode().equals(AptentResponseCodes.COMPLETED))
                                                logger.info("SMS SENT SUCCESSFULLY");

                                        }
                                        request.setTopUpTimes(0);
                                    } catch (Exception e) {
                                        request.setTopUpTimes(request.getTopUpTimes());
                                        e.printStackTrace();
                                    }
                                } else {
                                    /**
                                     * Update fail count and set the next payment date to the next day
                                     * Also send a sms to notify customer
                                     * */
                                    String message = "Kindly fund your account to prevent cancellation of top-up on GroFlex Investment.";
                                    request.setFailCount(request.getFailCount() + 1);
                                    request.setNextPaymentDate(request.getNextPaymentDate().plusDays(1));

                                    /***
                                     * For purpose of test
                                     * **/
//                                    request.setNextPaymentDate(request.getNextPaymentDate().plusMinutes(2));
                                    try {
                                        logger.info("in here3 ");
                                        NotificationServiceResponse response = fcmbMoneytorNotificationService.sendSms(request.getPhoneNumber() != null ? request.getPhoneNumber() : "", message, request.getAccountId());

                                        if (response.getNotificationServiceResponseCode().equals(AptentResponseCodes.COMPLETED))
                                            logger.info("executed successfully");
                                        else
                                            logger.info("unable to execute " + response);

                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                                try {
                                    logger.info("request " + request);
                                    Object o = groApiClient.postMethod("/save-top-up", request);
                                    if (o != null)
                                        logger.info("Top Up request saved");
                                    /**
                                     * 1. Send mail/ sms to customer notifying for successful top up
                                     * 2. Audit action
                                     **/
                                    auditService.save(request.getAccountId(), AuditActions.GRO_INVESTMENT_SERVICE.name(), "Debit successful", auditDomain);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    throw new CosmosServiceException(e.getMessage());
                                }
                            } else {
                                logger.error("Error from service ==> " + moneytorApiResponse.getNarration());
                            }

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    } else {
                        /**
                         * Debit was successful
                         **/
                        Integer topUpPeriod;
                        logger.info("Customer is debited");
                        if (request.getTopUpPolicy().equals(TopUpPolicy.MONTHLY)) {
//                            request.setNextPaymentDate(request.getNextPaymentDate().plusSeconds(30));
                            topUpPeriod = 30;
                            request.setNextPaymentDate(request.getNextPaymentDate().plusMonths(1));
                        } else if (request.getTopUpPolicy().equals(TopUpPolicy.QUARTERLY)) {
//                            request.setNextPaymentDate(request.getNextPaymentDate().plusSeconds(30));
                            topUpPeriod = 90;
                            request.setNextPaymentDate(request.getNextPaymentDate().plusMonths(3));

                        } else {
//                            request.setNextPaymentDate(request.getNextPaymentDate().plusSeconds(30));
                            topUpPeriod = 182;
                            request.setNextPaymentDate(request.getNextPaymentDate().plusMonths(6));
                        }
                        if (request.getFailCount() <= 0 || request.getFailCount() == null) {
                            request.setTopUpTimes(request.getTopUpTimes() - 1);
                            request.setTopUpAmountInvested(request.getTopupAmount() + request.getTopUpAmountInvested());
//                            logger.info("Interest " +calculatedTopInterest(request,topUpPeriod));
                            if (request.getTopUpAmountInvested() > 0 && (!request.getTopUpPolicy().equals(TopUpPolicy.NONE))) {
                                request.setCalculatedInterest(request.getCalculatedInterest() + calculateTopUpInterest(request, topUpPeriod));
                                request.setTenorLeft(request.getTenorLeft() - topUpPeriod);
                                logger.info("Cal interest " + request.getCalculatedInterest());
                            }
                        }
                        //save Top up request
                        try {
                            Object o = groApiClient.postMethod("/save-top-up", request);
//                            TopUpRequest topUpRequest = new Gson().fromJson(String.valueOf(o),TopUpRequest.class);
                            if (o != null)
                                logger.info("Top Up request saved");
                            /**
                             * 1. Send mail/ sms to customer notifying for successful top up
                             * 2. Audit action
                             **/
                            auditService.save(request.getAccountId(), AuditActions.GRO_INVESTMENT_SERVICE.name(), "Topup saved", auditDomain);
                        } catch (Exception e) {
                            throw new CosmosServiceException(e.getMessage());
                        }
                    }
                } else {
                    logger.info("No records passed validation");
                }
            }
        } else {
            logger.info("No records found");
        }

    }

    public void processDailyInterest(List<TopUpRequest> list) throws CosmosServiceException {
        for (TopUpRequest topUpRequest : list) {
            if (topUpRequest.getTopUpAmountInvested() > 0 && (!topUpRequest.getTopUpPolicy().equals(TopUpPolicy.NONE))) {
                Integer topUpPeriod = 0;
                try {
                    switch (topUpRequest.getTopUpPolicy()) {
                        case MONTHLY:
                            topUpPeriod = 30;
                            break;
                        case QUARTERLY:
                            topUpPeriod = 90;
                            break;
                        case SEMI_ANNUALLY:
                            topUpPeriod = 182;
                            break;

                    }

                    logger.info("Interest " + calculatedTopInterest(topUpRequest, topUpPeriod));
                    topUpRequest.setCalculatedInterest(topUpRequest.getCalculatedInterest() + calculatedTopInterest(topUpRequest, topUpPeriod));
                    topUpRequest.setTenorLeft(topUpRequest.getTenorLeft() - 1);
                    logger.info("Cal interest " + topUpRequest);

                    Object o = groApiClient.postMethod("/save-top-up", topUpRequest);
                    if (o != null)
                        logger.info("request saved for calculated interest");

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public Double calculatedTopInterest(TopUpRequest topUpRequest, Integer topUpPeriod) {
        Integer calculatedTenor = topUpRequest.getTenor() == 365 ? 1 : 2;
        logger.info("Top up period " + topUpPeriod);
        return (topUpRequest.getTopupAmount() * 9 / 100 / calculatedTenor * topUpPeriod / 365) / topUpPeriod;
    }

    public Double calculateTopUpInterest(TopUpRequest topUpRequest, Integer topUpPeriod) {
        Integer calculatedTenor = topUpRequest.getTenor() == 365 ? 1 : 2;
        return (topUpRequest.getTopupAmount() * 9 / 100 / calculatedTenor * topUpPeriod / 365);

    }

//    public static void main(String[] args) {
//
//      Double value =   ( 100000.0 * 9/100 * 90/365 );
//        System.out.println("Value " + value);
//
//        System.out.println(BigDecimal.valueOf(value).setScale(2,BigDecimal.ROUND_DOWN));
//    }

    public Double calculateDailyTopUpInterest(Double topUpInterest) {
        return topUpInterest / 30;
    }

    private InvestmentResponse getInvestmentResponseByInvestmentId(Long id) throws CosmosServiceException, IOException {
        return groApiClient.getInvestmentResponse("/get-investment-by-id/" + id);

//        return (InvestmentResponse) result;
    }

    private String generateBatchKey(String prefix, LocalDateTime date, Long investmentId) {
        return prefix + date.format(DateTimeFormatter.ofPattern("yyMMddHHmmss")) + investmentId;
    }

    public Object liquidateSingleInvestment(Principal principal, CreateGroInvestmentLiquidation request) throws CosmosServiceException {
        String narration = "";
        InvestmentResponse investmentResponse;
        User user;
        CustomerAccount creditAccount;
        try {
            user = userService.getUser(principal);
            investmentResponse = getInvestmentResponseByInvestmentId(request.getInvestmentId());

            String transId;

            if (investmentResponse.getProductName().contains("Dollar")) {
                transId = this.generateBatchKey("GRL", investmentResponse.getMaturityDate(), investmentResponse.getId()) + "GRD";
            } else {
                transId = this.generateBatchKey("GRNLD", investmentResponse.getMaturityDate(), investmentResponse.getId());
            }

            if (investmentResponse.getStatus() != InvestmentStatus.ACTIVE) {
                throw new CosmosServiceException("Unable to process request");
            }

            String creditAccountNumber = investmentResponse.getCreditAccount();

            creditAccount = accountService.getAccount(user, creditAccountNumber);

            logger.info("InvestmentID : " + investmentResponse.getId());
            logger.info("Investment Credit Account Number : " + creditAccount.getAccountNumber());

            validationService.validatePin(principal, creditAccount.getCbaCustomerId(), request.getPin());
            narration = investmentResponse.getProductName() + " LQD/" + transId;
            String remark = getProductShortName(investmentResponse.getProductName()) + "LQD/" + transId;

            LiquidationPreviewResponse calculatedLiquidation = calculateLiquidation(investmentResponse.getId());
            logger.info("Calculated Liquidation: " + calculatedLiquidation.toString());

            GroInvestmentLiquidationRequest apiRequest =
                    new GroInvestmentLiquidationRequest().withInvestmentResponse(investmentResponse, calculatedLiquidation);

            apiRequest.setCreditAccount(creditAccount.getAccountNumber());
            apiRequest.setDebitAccount(investmentResponse.getProductName().contains("Dollar") ? this.settlementAccountDollar : this.settlementAccount);
            apiRequest.setFullname(creditAccount.getAccountName());
            apiRequest.setEmail(creditAccount.getEmail());
            apiRequest.setPhoneNumber(creditAccount.getPhoneNumber() != null ? creditAccount.getPhoneNumber() : "");

//            apiRequest.setPayOutAmount(apiRequest.getPayOutAmount().setScale(2, RoundingMode.HALF_EVEN));// Round to 2 d.p mathematically for CPM to be able to process
            apiRequest.setPayOutAmount(apiRequest.getPayOutAmount().setScale(2, RoundingMode.DOWN));// Round to 2 d.p mathematically for CPM to be able to process

            if (StringUtils.isEmpty(request.getPin())) {
                throw new CosmosServiceException("transaction Pin is required");
            }

            TranHistoryRequest tranHistoryRequest = new TranHistoryRequest(investmentResponse.getId(), transId, transId);

            MoneytorApiResponse<IntraBankTransferResponse> moneytorApiResponse =
                    doTransfer(principal, investmentResponse, apiRequest.getPayOutAmount(), narration, remark, tranHistoryRequest);

            if (moneytorApiResponse.getResponseCode().equals(AptentResponseCodes.COMPLETED)) {
                IntraBankTransferResponse intraBankTransferResponse = moneytorApiResponse.getData();
                logger.info("Customer was successfully credited");
                auditService.save(user, AuditActions.SINGLE_TRANSFER_SERVICE.name(), "Credit successful");

                Object o;
                try {
                    o = groApiClient.postMethod("/liquidate-investment/", apiRequest);
                    if (o == null) {
                        throw new CosmosServiceException("Failed to complete investment liquidation");
                    } else {
                        auditService.save(user, AuditActions.GRO_INVESTMENT_SERVICE.name(), "Investment Liquidation Created");
                    }
                    return o;
                } catch (Exception ex) {
                    //customer was credited but the liquidation failed, hence reversal needed.
                    try {
                        if (intraBankTransferResponse != null) {
                            groServiceReversal(principal, intraBankTransferResponse, AuditActions.TAREGET_SAVINGS_SERVICE, "Sorry Gro investment Liquidation failed");
                        }
                    } catch (CosmosServiceException cse) {
                        // unable to complete reversal
                        cse.printStackTrace();
                    }
                    throw new CosmosServiceException("Sorry! Gro investment liquidation failed");
                }
            }
            else {
                throw new CosmosServiceException("Unable to Credit Account. Investment liquidation failed.");
            }

        } catch (CosmosServiceException e) {
            e.printStackTrace();
            /*
            if(!isValueGiven){
                //Perform reversal to settlement Account
                try {
                    groServiceReversal(principal,response,AuditActions.TAREGET_SAVINGS_SERVICE,
                            "Sorry Gro investment Liquidation failed");
                } catch (CosmosServiceException cosmosServiceException) {
                    cosmosServiceException.printStackTrace();
                }
            }
            */
            throw e;

        } catch (Exception e) {
            e.printStackTrace();
            throw new CosmosServiceException("Failed to Liquidate investment");
        }
    }

    public static String getProductShortName(String productName) {
        switch (productName) {
            case "GroMax":
                return "GMx";
            case "GroMonie":
                return "GMo";
            case "GroFlex":
                return "GFx";
            case "GroFlex Dollar":
                return "GFD";
            case "GroMax Dollar":
                return "GMD";
            default:
                return productName;
        }
    }

    private void groServiceReversal(Principal principal, IntraBankTransferResponse response, AuditActions auditActions, String genericExceptionMsg) throws CosmosServiceException {
        User user = userService.getUser(principal);
        try {
            TransferReversalRequest transferReversalRequest = new TransferReversalRequest();
            transferReversalRequest.setOriginalCustomerReference(response.getCustomerReference());
            transferReversalRequest.setStan(response.getStan());
            MoneytorApiResponse<Object> reversal = intrabankTransferService.reversal(transferReversalRequest);
            if (!AptentResponseCodes.COMPLETED.equals(reversal.getResponseCode()))
                throw new CosmosServiceException(reversal == null ? "null response" : reversal.getNarration());
            auditService.save(user, auditActions.name(), "Reversal successful");
            logger.info("reversal successful");
        } catch (Exception ex) {
            logger.error("reversal failed: " + ex.getMessage());
            auditService.save(user, auditActions.name(), String.format("Reversal failed. OriginalCustomerReference: %s " +
                    "and stan: %s", response.getCustomerReference(), response.getStan()));
            throw new CosmosServiceException(genericExceptionMsg);
        }
    }

    private double computeInterest(double amount, double rate, int tenor) {
        return (amount * rate / 365 * tenor / 100);
    }

    public double computeTopUpInterest(double amount, double rate, int topUpPeriod, Integer tenor) {
        return (amount * rate / 100 / (tenor == 365 ? 1 : 2) * topUpPeriod / 365);
    }

    public boolean currentDateBeforeDefStartDate(LocalDateTime startDate) {
        if (startDate == null) return false;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        LocalDateTime definedTime = LocalDateTime.parse(String.format("%s %s", startDate.toLocalDate(), definedStartTime), formatter);
        return LocalDateTime.now().compareTo(definedTime) < 0;
    }

    public long getDaysBetween(LocalDateTime lastUpdatedDate, LocalDateTime maturityDate) {
        if (lastUpdatedDate == null || maturityDate == null) return 0;
        if (maturityDate.isAfter(LocalDateTime.now()))
            return ChronoUnit.DAYS.between(lastUpdatedDate.toLocalDate(), LocalDate.now());
        return ChronoUnit.DAYS.between(lastUpdatedDate.toLocalDate(), maturityDate.toLocalDate());
    }

    @Async
    public void performInterestOperation(List<InvestmentResponse> investments) {
        for (InvestmentResponse investment : investments) {
            if (this.currentDateBeforeDefStartDate(investment.getStartDate())) {
                logger.info("Investment with id {} and start date {} is new and not yet up till defined start time {}", investment.getId(), investment.getStartDate(), String.format("%s %s", investment.getStartDate().toLocalDate(), definedStartTime));
                continue;
            }

//            if(investment.getTenorLeft() == 0) {
//                logger.info("Investment with id {} tenor left is ZERO", investment.getId());
//                continue;
//            }

            if (investment.getStatus() != InvestmentStatus.ACTIVE) {
                logger.info("Investment with id {} is not ACTIVE", investment.getId());
                continue;
            }

            logger.info("Investment with id {} is ACTIVE. Moving to check condition", investment.getId());

            BigDecimal accruedInterest = investment.getAccruedInterest();
            double dailyInterest = this.computeInterest(investment.getPrincipal().doubleValue(), investment.getRate(), investment.getTenor()) / investment.getTenor();

            long totalDaysInvested = investment.getTenorLeft() == 0 ? investment.getTenor() : this.getDaysBetween(investment.getStartDate(), investment.getMaturityDate());
            BigDecimal bigDecimal = BigDecimal.valueOf(totalDaysInvested == 0 ? 1 : totalDaysInvested);
            BigDecimal expectedInterestFromStart = BigDecimal.valueOf(dailyInterest).multiply(bigDecimal);
            if (accruedInterest.compareTo(expectedInterestFromStart) < 0) {
                // Interest value not up till date...
                logger.info("Performing interest operation for investment with ID: " + investment.getId());
                logger.info("Daily interest {}, Computed interest: {}, Investment ID {}", dailyInterest, expectedInterestFromStart, investment.getId());
                investment.setAccruedInterest(expectedInterestFromStart);
                investment.setTenorLeft(investment.getTenor() - Long.valueOf(totalDaysInvested).intValue());
                investment.setLastUpdatedDate(LocalDateTime.now());
                try {
                    groApiClient.postMethod("/update-investment", investment);
                } catch (CosmosServiceException e) {
                    e.printStackTrace();
                }

                // Check if customer has topup and update daily topup interest
                TopUpRequest topUpRequest = investment.getTopUpRequest();
                if (topUpRequest != null && topUpRequest.getTopUpAmountInvested() > 0 && !topUpRequest.getTopUpPolicy().equals(TopUpPolicy.NONE)) {
                    int topUpPeriod = 0;
                    int monthsToSkip = 0;
                    try {
                        switch (topUpRequest.getTopUpPolicy()) {
                            case MONTHLY:
                                topUpPeriod = 30;
                                monthsToSkip = 1;
                                break;
                            case QUARTERLY:
                                topUpPeriod = 90;
                                monthsToSkip = 3;
                                break;
                            case SEMI_ANNUALLY:
                                topUpPeriod = 182;
                                monthsToSkip = 6;
                                break;
                        }

                        int topUpTimes = Double.valueOf(topUpRequest.getTopUpAmountInvested() / topUpRequest.getTopupAmount()).intValue();
//                        double expectedTopUpInterest = this.getExpectedTopUpInterest(topUpRequest, topUpTimes, investment.getRate(), topUpPeriod);
                        double expectedTopUpInterest = this.getExpectedTopUpSimpleInterest(topUpRequest, topUpTimes, investment.getRate(), topUpPeriod);

                        if (topUpRequest.getCalculatedInterest().compareTo(expectedTopUpInterest) != 0) {
                            topUpRequest.setCalculatedInterest(expectedTopUpInterest);
                            topUpRequest.setTenorLeft(investment.getTenorLeft());// Defaulting tenor left to investment tenor left to ensure sync
                            groApiClient.postMethod("/save-top-up", topUpRequest);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private boolean isCurrentMonth(LocalDateTime localDateTime) {
        Calendar calendar = Calendar.getInstance();
        Calendar calendar2 = Calendar.getInstance();
        calendar.setTime(Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant()));
        calendar2.setTime(new Date());
        if (calendar.get(Calendar.YEAR) == calendar2.get(Calendar.YEAR))
            return calendar.get(Calendar.MONTH) == calendar2.get(Calendar.MONTH);
        return false;
    }

    private boolean isCurrentMonth(LocalDateTime firstDateTime, LocalDateTime secondDateTime) {
        Calendar calendar = Calendar.getInstance();
        Calendar calendar2 = Calendar.getInstance();
        calendar.setTime(Date.from(firstDateTime.atZone(ZoneId.systemDefault()).toInstant()));
        calendar2.setTime(Date.from(secondDateTime.atZone(ZoneId.systemDefault()).toInstant()));
        if (calendar.get(Calendar.YEAR) == calendar2.get(Calendar.YEAR))
            return calendar.get(Calendar.MONTH) == calendar2.get(Calendar.MONTH);
        return false;
    }

    //    Compound interest calculation
    private double getExpectedTopUpInterest(TopUpRequest topUpRequest, int topUpTimes, double rate, int topUpPeriod) {
        double addedPrincipal = topUpRequest.getTopupAmount();
        LocalDateTime currentTime = LocalDateTime.now();
        LocalDateTime today = topUpRequest.getTenorLeft() == 0 ? topUpRequest.getMaturityDate() : currentTime;

        double expectedInterest = 0;
        boolean isCurrentMonth = false;

        LocalDateTime topUpStartDate = topUpRequest.getInvestmentCreationTime();
        LocalDateTime investmentCreationTime = topUpRequest.getInvestmentCreationTime();
        boolean isLastTopUp;

        for (int i = 0; i < topUpTimes; i++) {
            isLastTopUp = i == topUpTimes - 1;
            investmentCreationTime = i == 0 ?
                    topUpRequest.getInvestmentCreationTime() :
                    investmentCreationTime.plusDays(topUpPeriod);
            LocalDateTime topUpStartMonth = investmentCreationTime.plusDays(topUpPeriod);
            LocalDateTime topUpEndMonth = topUpStartMonth.plusDays(topUpPeriod);

            isCurrentMonth = isLastTopUp &&
                    (this.isCurrentMonth(topUpStartMonth, today) || this.isCurrentMonth(topUpEndMonth, today));

            if (isCurrentMonth)
                topUpEndMonth = today;

            long daysBetweenMonths = ChronoUnit.DAYS.between(topUpStartMonth, topUpEndMonth);

            double interestForPeriod = this.computeTopUpInterest(addedPrincipal, rate, topUpPeriod, topUpRequest.getTenor());
            double dailyInterestForPeriod = interestForPeriod / topUpPeriod;

            expectedInterest += (dailyInterestForPeriod * (daysBetweenMonths + (isCurrentMonth ? 1 : 0)));

            if (i < (topUpTimes - 1))
                addedPrincipal += topUpRequest.getTopupAmount();
        }

        LocalDateTime loopedTopUpTime = topUpStartDate.plusDays(topUpPeriod + ((long) topUpTimes * topUpPeriod));

        if (!isCurrentMonth) {
            long daysBetweenLastTopAndToday = ChronoUnit.DAYS.between(loopedTopUpTime, today) + 1;
            double monthly = this.computeTopUpInterest(addedPrincipal, rate, topUpPeriod, topUpRequest.getTenor());
            double daily = monthly / topUpPeriod;
            expectedInterest += (daily * daysBetweenLastTopAndToday);
        }

        System.out.println("Expected top-up interest: " + expectedInterest);
        return expectedInterest;
    }

    private double getExpectedTopUpSimpleInterest(TopUpRequest topUpRequest, int topUpTimes, double rate, int topUpPeriod) {
        LocalDateTime currentTime = LocalDateTime.now();
        LocalDateTime today = topUpRequest.getTenorLeft() == 0 ? topUpRequest.getMaturityDate() : currentTime;

        double expectedInterest = 0;
        boolean isCurrentMonth;

        LocalDateTime investmentCreationTime = topUpRequest.getInvestmentCreationTime();
        boolean isLastTopUp;

        for (int i = 0; i < topUpTimes; i++) {
            isLastTopUp = i == topUpTimes - 1;
            investmentCreationTime = i == 0 ?
                    topUpRequest.getInvestmentCreationTime() :
                    investmentCreationTime.plusDays(topUpPeriod);
            LocalDateTime topUpStartMonth = investmentCreationTime.plusDays(topUpPeriod);
            LocalDateTime topUpEndMonth = topUpStartMonth.plusDays(topUpPeriod);

            isCurrentMonth = isLastTopUp &&
                    (this.isCurrentMonth(topUpStartMonth, today) || this.isCurrentMonth(topUpEndMonth, today));

            if (isCurrentMonth)
                topUpEndMonth = today;

            long daysBetweenMonths = ChronoUnit.DAYS.between(topUpStartMonth, topUpEndMonth);

            double interestForPeriod = this.computeTopUpInterest(topUpRequest.getTopupAmount(), rate, topUpPeriod, topUpRequest.getTenor());
            double dailyInterestForPeriod = interestForPeriod / topUpPeriod;

            if (daysBetweenMonths >= topUpPeriod)
                expectedInterest += interestForPeriod;
            else
                expectedInterest += (dailyInterestForPeriod * (daysBetweenMonths + (isCurrentMonth ? 1 : 0)));
        }

        System.out.println("Expected top-up interest: " + expectedInterest);
        return expectedInterest;
    }

//
//    private double getExpectedTopUpInterest(double initPrincipal, int tenor, int tenorLeft, int topUpTimes, double rate, int topUpPeriod, LocalDateTime startDate, LocalDateTime endDate, int monthsToSkip) {
//        double addedPrincipal = initPrincipal;
//        LocalDateTime topUpStartDate = startDate.plusMonths(monthsToSkip);
//        LocalDateTime currentTime = LocalDateTime.now();
//        LocalDateTime today = tenorLeft == 0 ? endDate : currentTime;
//        double expectedInterest = 0;
//        LocalDateTime lastTrackedMonth = LocalDateTime.now();
//
//        for(int i = 1; i <= topUpTimes; i++) {
//            LocalDateTime trackedMonth = topUpStartDate.plusMonths(i);
//            boolean isCurrentMonth = isCurrentMonth(trackedMonth);
//            lastTrackedMonth = trackedMonth;
//            if(isCurrentMonth) {
//                double monthly = computeTopUpInterest(addedPrincipal, rate, topUpPeriod, tenor);
//                double daily = monthly / topUpPeriod;
//                long daysBetween = ChronoUnit.DAYS.between(trackedMonth.toLocalDate().minusDays(topUpPeriod), endDate.toLocalDate());
//
//                expectedInterest += (daily * daysBetween);
//            }else {
//                double interestForPeriod = computeTopUpInterest(addedPrincipal, rate, topUpPeriod, tenor);
//                expectedInterest += interestForPeriod;
//                if(i < topUpTimes)
//                    addedPrincipal += initPrincipal;
//            }
//        }
//
//        if(today.isAfter(lastTrackedMonth)) {
//            long daysBetween = ChronoUnit.DAYS.between(lastTrackedMonth, today);
//            double monthly = computeTopUpInterest(addedPrincipal, rate, topUpPeriod, tenor);
//            double daily = monthly / topUpPeriod;
//            expectedInterest += (daily * daysBetween);
//        }
//
//        System.out.println("Expected interest: "+ expectedInterest);
//        return expectedInterest;
//    }
//
//    @Async//TODO...
//    public void performInterestOperation(List<InvestmentResponse> investments) {
//        for(InvestmentResponse investment : investments) {
//            if(this.currentDateBeforeDefStartDate(investment.getStartDate())) {
//                logger.info("Investment with id {} and start date {} is new and not yet up till defined start time {}", investment.getId(), investment.getStartDate(), String.format("%s %s", investment.getStartDate().toLocalDate(), definedStartTime));
//                continue;
//            }
//
////            if(investment.getTenorLeft() == 0) {
////                logger.info("Investment with id {} tenor left is ZERO", investment.getId());
////                continue;
////            }
//
//            if(investment.getStatus() != InvestmentStatus.ACTIVE) {
//                logger.info("Investment with id {} is not ACTIVE", investment.getId());
//                continue;
//            }
//
//            logger.info("Investment with id {} is ACTIVE. Moving to check condition", investment.getId());
//
//            BigDecimal accruedInterest = investment.getAccruedInterest();
//            double dailyInterest = this.computeInterest(investment.getPrincipal().doubleValue(), investment.getRate(), investment.getTenor()) / investment.getTenor();
//
//            long totalDaysInvested = investment.getTenorLeft() == 0 ? investment.getTenor() : this.getDaysBetween(investment.getStartDate(), investment.getMaturityDate());
//            BigDecimal expectedInterestFromStart = BigDecimal.valueOf(dailyInterest).multiply(BigDecimal.valueOf(totalDaysInvested == 0 ? 1 : totalDaysInvested));
////            if(accruedInterest.compareTo(expectedInterestFromStart) < 0) {//Todo
//                // Interest value not up till date...
//                logger.info("Performing interest operation for investment with ID: "+ investment.getId());
//                logger.info("Daily interest {}, Computed interest: {}, Investment ID {}", dailyInterest, expectedInterestFromStart, investment.getId());
//                investment.setAccruedInterest(expectedInterestFromStart);
//                investment.setTenorLeft(investment.getTenor() - Long.valueOf(totalDaysInvested).intValue());
//                investment.setLastUpdatedDate(LocalDateTime.now());
////                try {
////                    groApiClient.postMethod("/update-investment", investment);
////                } catch (CosmosServiceException e) {
////                    e.printStackTrace();
////                }//TODO...
//
//                // Check if customer has topup and update daily topup interest
//                TopUpRequest topUpRequest = investment.getTopUpRequest();
//                logger.info("Tr: "+ topUpRequest.toString());
//                if(topUpRequest != null && topUpRequest.getTopUpAmountInvested() > 0 && !topUpRequest.getTopUpPolicy().equals(TopUpPolicy.NONE)){
//                    int topUpPeriod = 0;
//                    try{
//                        switch (topUpRequest.getTopUpPolicy()){
//                            case MONTHLY:
//                                topUpPeriod  = 30;
////                                totalDaysInvested = topUpRequest.getTopUpAmountInvested() >= (topUpRequest.getTopupAmount() * 2) ?
////                                        totalDaysInvested - 30 : totalDaysInvested;
////                                if(topUpRequest.getTopUpAmountInvested() > 0) {
////                                    Double actualTopUpTimes = topUpRequest.getTopUpAmountInvested() / topUpRequest.getTopupAmount();
////                                    totalDaysInvested = (topUpPeriod * actualTopUpTimes.longValue()) + topUpPeriod;
////                                }
//                                break;
//                            case QUARTERLY:
//                                topUpPeriod = 90;
////                                totalDaysInvested = topUpRequest.getTopUpAmountInvested() >= (topUpRequest.getTopupAmount() * 2) ?
////                                        totalDaysInvested - 30 : totalDaysInvested;
////                                if(topUpRequest.getTopUpAmountInvested() > 0) {
////                                    Double actualTopUpTimes = topUpRequest.getTopUpAmountInvested() / topUpRequest.getTopupAmount();
////                                    totalDaysInvested = (topUpPeriod * actualTopUpTimes.longValue()) + topUpPeriod;
////                                }
//                                break;
//                            case SEMI_ANNUALLY:
//                                topUpPeriod = 182;
////                                totalDaysInvested = topUpRequest.getTenorLeft() < (topUpRequest.getTenor() - 30) ?
////                                        totalDaysInvested - 30 : totalDaysInvested;
////                                if(topUpRequest.getTopUpAmountInvested() > 0) {
////                                    Double actualTopUpTimes = topUpRequest.getTopUpAmountInvested() / topUpRequest.getTopupAmount();
////                                    totalDaysInvested = (topUpPeriod * actualTopUpTimes.longValue()) + topUpPeriod;
////                                }
//                                break;
//                        }
//
////                        topup period * totalAmountinv / amount inv + topUpPeriod provided amount invested is greater than zero
//                        totalDaysInvested = topUpRequest.getTenorLeft() < (topUpRequest.getTenor() - 30) ?
//                                totalDaysInvested - 30 : totalDaysInvested;
//
//                        System.out.println("Total days invested: "+ totalDaysInvested);
//
//                        Double topUpAmount = topUpRequest.getTopupAmount();
//                        if(topUpRequest.getTenorLeft() == 0)
//                            topUpAmount = topUpRequest.getTopUpAmountInvested() - topUpRequest.getTopupAmount();
//
//                        System.out.println("To A: "+ topUpAmount);
//
//                        double dailyTopUpInterest = computeTopUpInterest(topUpAmount, investment.getRate(), topUpPeriod, topUpRequest.getTenor()) / topUpPeriod;
////                        double dailyTopUpInterest = computeTopUpInterest(topUpRequest.getTopUpAmountInvested(), investment.getRate(), topUpPeriod, topUpRequest.getTenor()) / topUpPeriod;
//                        BigDecimal expectedTopUpInterestFromStart = BigDecimal.valueOf(dailyTopUpInterest).multiply(BigDecimal.valueOf(totalDaysInvested == 0 ? 1 : totalDaysInvested));
//                        logger.info("Daily TOP_UP interest {}, Computed interest: {}, Investment ID {}, TOP_UP_ID {}", dailyTopUpInterest, expectedTopUpInterestFromStart, investment.getId(), topUpRequest.getId());
//                        topUpRequest.setCalculatedInterest(expectedTopUpInterestFromStart.doubleValue());
//                        topUpRequest.setTenorLeft(topUpRequest.getTenor() - Long.valueOf(totalDaysInvested).intValue());
//                        logger.info("Modified top up request " + topUpRequest);
//
//
//                        this.getExpectedInterest(topUpRequest.getInvestmentCreationTime(), LocalDateTime.now(), )
//
//
////                        groApiClient.postMethod("/save-top-up",topUpRequest);//TODO...
//                    }catch (Exception e){
//                        e.printStackTrace();
//                    }
//                }
////            }//todo
//        }
//    }
//
//    private static void compu() {
//        double initPrincipal = 100;
//        double addedPrincipal = initPrincipal;
//        int tenor = 182;
//        int topUpTimes = 4;
//        int rate = 6;
//        int topUpPeriod = 30;
//        LocalDateTime startDate = LocalDateTime.parse("2022-05-31 13:42", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
//        LocalDateTime topUpStartDate = LocalDateTime.parse("2022-05-31 13:42", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).plusMonths(1);
//        LocalDateTime endDate = LocalDateTime.parse("2022-11-29 13:42", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
////        LocalDateTime today = LocalDateTime.now();
//        LocalDateTime today = LocalDateTime.parse("2022-11-30 13:42", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
//        System.out.println("Start date: "+ startDate + "... TP start date: "+ topUpStartDate + "... End date: "+ endDate + "... Today: "+ today);
//        double expectedInterest = 0;
//        LocalDateTime lastTrackedMonth = LocalDateTime.now();
//        for(int i = 1; i <= topUpTimes; i++) {
//            LocalDateTime trackedMonth = topUpStartDate.plusMonths(i);
//            System.out.println("TM: "+ trackedMonth + " I is: "+ i);
//            boolean isCurrentMonth = isCurrentMonth(trackedMonth);
//            System.out.println("is current month: "+ isCurrentMonth);
//            lastTrackedMonth = trackedMonth;
//            if(isCurrentMonth) {
//                System.out.println("Added principal: "+ addedPrincipal);
//                double monthly = computeTopUpInterestN(addedPrincipal, rate, topUpPeriod, tenor);
//                System.out.println("Monthly: "+ monthly);
//                double daily = monthly / topUpPeriod;
//                System.out.println("Daily: "+ daily);
//
//                long daysBetween = ChronoUnit.DAYS.between(trackedMonth.toLocalDate().minusDays(topUpPeriod), LocalDate.parse("2022-11-29"));
//                System.out.println("Days between: "+ daysBetween);
//
//                expectedInterest += (daily * daysBetween);
//
//                System.out.println("Expected interest..."+ expectedInterest);
//            }else {
//                double monthly = computeTopUpInterestN(addedPrincipal, rate, topUpPeriod, tenor);
//
//                System.out.println("Init P B: "+ addedPrincipal);
//                System.out.println("m: "+ monthly);
//
//                expectedInterest += monthly;
//
//                System.out.println("Expected interest..."+ expectedInterest);
//
//                if(i < topUpTimes)
//                    addedPrincipal += initPrincipal;
//
//                System.out.println("Init P: "+ addedPrincipal);
//            }
//        }
//
//        // End date should be replaced with current date...
//        if(today.isAfter(lastTrackedMonth)) {
//            LocalDateTime usedDate = today.isBefore(endDate) ? today : endDate;
//            System.out.println("Used date: "+ usedDate);
//            long daysBetween = ChronoUnit.DAYS.between(lastTrackedMonth, usedDate);
//            System.out.println("LTM: " + lastTrackedMonth + " End Date: " + endDate + " DB: " + daysBetween);
//            double monthly = computeTopUpInterestN(addedPrincipal, rate, topUpPeriod, tenor);
//            double daily = monthly / topUpPeriod;
//            expectedInterest += (daily * daysBetween);
//        }
//
//        System.out.println("Final expected interest: "+ expectedInterest);
//    }
//
//    private static double getTopUpExpectedInterest(double initPrincipal, int tenor, int tenorLeft, int topUpTimes, double rate, int topUpPeriod, LocalDateTime startDate, LocalDateTime endDate, int monthsToSkip) {
////        double initPrincipal = 100;
//        double addedPrincipal = initPrincipal;
////        int tenor = 182;
////        int topUpTimes = 4;
////        int rate = 6;
////        int topUpPeriod = 30;
////        LocalDateTime startDate = LocalDateTime.parse("2022-05-31 13:42", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
//        LocalDateTime topUpStartDate = startDate.plusMonths(monthsToSkip);
////        LocalDateTime endDate = LocalDateTime.parse("2022-11-29 13:42", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
//        LocalDateTime currentTime = LocalDateTime.now();
//        LocalDateTime today = tenorLeft == 0 ? endDate : currentTime;
//        System.out.println("Tenor left: "+ tenorLeft);
//        System.out.println("Today: "+ today);
//        double expectedInterest = 0;
//        LocalDateTime lastTrackedMonth = LocalDateTime.now();
//        for(int i = 1; i <= topUpTimes; i++) {
//            LocalDateTime trackedMonth = topUpStartDate.plusMonths(i);
//            System.out.println("TM: "+ trackedMonth + " I is: "+ i);
//            boolean isCurrentMonth = isCurrentMonth(trackedMonth);
//            System.out.println("is current month: "+ isCurrentMonth);
//            lastTrackedMonth = trackedMonth;
//            if(isCurrentMonth) {
//                System.out.println("Added principal: "+ addedPrincipal);
//                double monthly = computeTopUpInterestN(addedPrincipal, rate, topUpPeriod, tenor);
//                System.out.println("Monthly: "+ monthly);
//                double daily = monthly / topUpPeriod;
//                System.out.println("Daily: "+ daily);
//
//                long daysBetween = ChronoUnit.DAYS.between(trackedMonth.toLocalDate().minusDays(topUpPeriod), LocalDate.parse("2022-11-29"));
//                System.out.println("Days between: "+ daysBetween);
//
//                expectedInterest += (daily * daysBetween);
//
//                System.out.println("Expected interest..."+ expectedInterest);
//            }else {
//                double monthly = computeTopUpInterestN(addedPrincipal, rate, topUpPeriod, tenor);
//
//                System.out.println("Init P B: "+ addedPrincipal);
//                System.out.println("m: "+ monthly);
//
//                expectedInterest += monthly;
//
//                System.out.println("Expected interest..."+ expectedInterest);
//
//                if(i < topUpTimes)
//                    addedPrincipal += initPrincipal;
//
//                System.out.println("Init P: "+ addedPrincipal);
//            }
//        }
//
//        System.out.println("Condition: "+ today.isAfter(lastTrackedMonth));
//        if(today.isAfter(lastTrackedMonth)) {
////            LocalDateTime usedDate = today.isBefore(endDate) ? today : endDate;
////            System.out.println("Used date: "+ usedDate);
////            long daysBetween = ChronoUnit.DAYS.between(lastTrackedMonth, usedDate);
//            long daysBetween = ChronoUnit.DAYS.between(lastTrackedMonth, today);
//            System.out.println("LTM: " + lastTrackedMonth + " End Date: " + endDate + " DB: " + daysBetween);
//            double monthly = computeTopUpInterestN(addedPrincipal, rate, topUpPeriod, tenor);
//            double daily = monthly / topUpPeriod;
//            expectedInterest += (daily * daysBetween);
//        }
//
//        System.out.println("Final expected interest: "+ expectedInterest);
//
//        return expectedInterest;
//    }
//
//    public BigDecimal getExpectedInterest(LocalDateTime startDate, LocalDateTime stopDate, int totalTopUps, double periodicInvestment, int topUpPeriod) {
//        System.out.println("ST: "+ startDate);
//        System.out.println("STD: "+ stopDate);
//        LocalDateTime topUpStartTime = startDate.plusMonths(totalTopUps + 1);
//        System.out.println("TST: "+ topUpStartTime);
//        long totalDaysInvested = this.getDaysBetween(topUpStartTime);
//        System.out.println("TDI: "+ totalDaysInvested);
//        double dailyPeriodic = periodicInvestment / topUpPeriod;
//        System.out.println("Daily periodic: "+ dailyPeriodic);
//        BigDecimal finalValue = BigDecimal.valueOf(dailyPeriodic).multiply(BigDecimal.valueOf(totalDaysInvested));
//        System.out.println("Final value: "+ finalValue);
//        return finalValue;
//    }
//
//    public static void computeDaily() {
//        double ti = computeTopUpInterest(100, 6, 30, 182);
//        double dti = ti / 30;
//        System.out.println("1: "+ dti);// Day 1 -- 1st July after skipping June
//        System.out.println("30: "+ dti * 30);// Month 1 -- 30th July
//        System.out.println("50: "+ dti * 50);
//        System.out.println("60: "+ dti * 60);// Month 2 -- 30th August
//        System.out.println("90: "+ dti * 90);// Month 3 -- 30th September
//        System.out.println("120: "+ dti * 120);// Month 4 -- 30th October
//        System.out.println("150: "+ dti * 150);
//        System.out.println("152: "+ dti * 152);
//    }
//
//    public static void main(String[] args) {
//        computeDaily();
//        System.out.println(LocalDateTime.parse("2022-05-31 13:42", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).plusDays(182));
//        System.out.println(LocalDateTime.parse("2022-05-31 13:42", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).plusMonths(4));
//        System.out.println(LocalDateTime.parse("2022-05-31 13:42", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).plusDays(120));
//        System.out.println(LocalDateTime.parse("2022-05-31 13:42", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).plusMonths(6));
//        compu();
//    }
}
